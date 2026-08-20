package com.shvoy;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserStatusType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

/**
 * Real Cognito-backed IdentityProvider (dev/prod only — see LocalIdentityProvider
 * for local/test). Uses admin-create-user immediately followed by
 * admin-set-user-password(permanent=true) rather than leaving the user in
 * Cognito's FORCE_CHANGE_PASSWORD state: that lets RegistrationService.activate's
 * existing "token + chosen password in one request" UX carry over unchanged,
 * instead of requiring a second, separate NEW_PASSWORD_REQUIRED challenge
 * round-trip through the frontend.
 */
@Component
@Profile("!local & !test")
class CognitoIdentityProvider implements IdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(CognitoIdentityProvider.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!@#$%^&*-_=+";

    private final CognitoIdentityProviderClient cognitoClient;
    private final String userPoolId;

    CognitoIdentityProvider(CognitoIdentityProviderClient cognitoClient,
            @Value("${cognito.user-pool-id}") String userPoolId) {
        this.cognitoClient = cognitoClient;
        this.userPoolId = userPoolId;
    }

    @Override
    public String createConfirmedUser(String email, String password) {
        String sub = createUser(email, false);

        // If setting the permanent password fails, the create above must
        // not survive it: a user left in FORCE_CHANGE_PASSWORD blocks
        // every future attempt for this email with UsernameExists —
        // observed live (dev pool, 2026-08-20) three activations in a
        // row. Compensate by deleting the half-created user before
        // rethrowing, so the caller's retry starts from a clean slate.
        try {
            cognitoClient.adminSetUserPassword(b -> b
                .userPoolId(userPoolId)
                .username(email)
                .password(password)
                .permanent(true));
        } catch (InvalidPasswordException e) {
            deleteHalfCreatedUser(email);
            // The pool's policy rejected the caller's chosen password —
            // the caller's mistake, not a server fault: a 400 with its
            // own code (the frontend maps INVALID_PASSWORD to "fix your
            // password" copy), carrying Cognito's own reason, which
            // names the exact rule broken.
            throw new ValidationException(ErrorCode.INVALID_PASSWORD, passwordRejectionReason(e));
        } catch (RuntimeException e) {
            deleteHalfCreatedUser(email);
            throw e;
        }

        return sub;
    }

    /**
     * One reclaim, then create again — never loops. A resident user in
     * FORCE_CHANGE_PASSWORD state can only be the debris of a previous
     * create-then-set-password failure (this class is the sole creator of
     * pool users and always sets the permanent password within the same
     * call), so it has never held a working credential and is safe to
     * delete. A CONFIRMED resident is a real identity — never touched;
     * the UsernameExists propagates for manual investigation.
     */
    private String createUser(String email, boolean reclaimAttempted) {
        AdminCreateUserResponse createResponse;
        try {
            createResponse = cognitoClient.adminCreateUser(b -> b
                .userPoolId(userPoolId)
                .username(email)
                .userAttributes(
                    AttributeType.builder().name("email").value(email).build(),
                    AttributeType.builder().name("email_verified").value("true").build())
                .messageAction(MessageActionType.SUPPRESS)
                .temporaryPassword(generateTemporaryPassword()));
        } catch (UsernameExistsException e) {
            if (reclaimAttempted || !isReclaimableOrphan(email)) {
                throw e;
            }
            log.info("Reclaiming orphaned FORCE_CHANGE_PASSWORD Cognito user for {}", email);
            cognitoClient.adminDeleteUser(b -> b.userPoolId(userPoolId).username(email));
            return createUser(email, true);
        }

        return createResponse.user().attributes().stream()
            .filter(attr -> "sub".equals(attr.name()))
            .findFirst()
            .map(AttributeType::value)
            .orElseThrow(() -> new IllegalStateException("Cognito did not return a sub for " + email));
    }

    private boolean isReclaimableOrphan(String email) {
        var user = cognitoClient.adminGetUser(b -> b.userPoolId(userPoolId).username(email));
        return user.userStatus() == UserStatusType.FORCE_CHANGE_PASSWORD;
    }

    /** Best-effort, like RegistrationService's own compensation: a failed delete is logged, and the original failure still propagates. */
    private void deleteHalfCreatedUser(String email) {
        try {
            cognitoClient.adminDeleteUser(b -> b.userPoolId(userPoolId).username(email));
        } catch (RuntimeException e) {
            log.warn("Failed to delete half-created Cognito user for {} after a set-password failure — "
                + "the next attempt will reclaim it as a FORCE_CHANGE_PASSWORD orphan", email, e);
        }
    }

    /** Cognito's own reason ("Password does not conform to policy: ...") without the SDK's request-id noise. */
    private static String passwordRejectionReason(InvalidPasswordException e) {
        return e.awsErrorDetails() != null && e.awsErrorDetails().errorMessage() != null
            ? e.awsErrorDetails().errorMessage()
            : "Password does not conform to the password policy";
    }

    @Override
    public void deleteUser(String email) {
        cognitoClient.adminDeleteUser(b -> b.userPoolId(userPoolId).username(email));
    }

    /**
     * Immediately overwritten by admin-set-user-password, so its value never
     * reaches a user — it only needs to satisfy the pool's password policy
     * so admin-create-user itself doesn't reject it.
     */
    private static String generateTemporaryPassword() {
        List<String> required = List.of(
            String.valueOf(UPPER.charAt(RANDOM.nextInt(UPPER.length()))),
            String.valueOf(LOWER.charAt(RANDOM.nextInt(LOWER.length()))),
            String.valueOf(DIGITS.charAt(RANDOM.nextInt(DIGITS.length()))),
            String.valueOf(SYMBOLS.charAt(RANDOM.nextInt(SYMBOLS.length()))));
        String allClasses = UPPER + LOWER + DIGITS + SYMBOLS;
        StringBuilder password = new StringBuilder(String.join("", required));
        for (int i = required.size(); i < 16; i++) {
            password.append(allClasses.charAt(RANDOM.nextInt(allClasses.length())));
        }
        // Shuffle so the required classes aren't always in the same leading positions.
        List<Character> chars = new ArrayList<>();
        password.chars().forEach(c -> chars.add((char) c));
        Collections.shuffle(chars, RANDOM);
        StringBuilder shuffled = new StringBuilder(chars.size());
        chars.forEach(shuffled::append);
        return shuffled.toString();
    }
}
