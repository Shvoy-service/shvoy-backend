package com.shvoy.onboarding.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Shared by every flow that issues a single-use link token (self-registration
 * in RegistrationService, invites in InvitationService, both funnelling into
 * the same {@code /activate} endpoint): a SecureRandom raw token for the
 * link itself, and its SHA-256 hash for storage. SHA-256 rather than a
 * salted/slow hash like bcrypt — the token is already high-entropy and
 * single-use, so bcrypt's brute-force resistance (built for low-entropy
 * passwords) buys nothing here, and a deterministic hash is what makes an
 * equality lookup by token possible at all.
 */
final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private SecureTokens() {
    }

    static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
