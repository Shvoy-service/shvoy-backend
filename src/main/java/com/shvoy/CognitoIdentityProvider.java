package com.shvoy;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;

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
        var createResponse = cognitoClient.adminCreateUser(b -> b
            .userPoolId(userPoolId)
            .username(email)
            .userAttributes(
                AttributeType.builder().name("email").value(email).build(),
                AttributeType.builder().name("email_verified").value("true").build())
            .messageAction(MessageActionType.SUPPRESS)
            .temporaryPassword(generateTemporaryPassword()));

        String sub = createResponse.user().attributes().stream()
            .filter(attr -> "sub".equals(attr.name()))
            .findFirst()
            .map(AttributeType::value)
            .orElseThrow(() -> new IllegalStateException("Cognito did not return a sub for " + email));

        cognitoClient.adminSetUserPassword(b -> b
            .userPoolId(userPoolId)
            .username(email)
            .password(password)
            .permanent(true));

        return sub;
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
