package com.fixloop.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * Creates opaque refresh tokens and stores only their SHA-256 hash, so a
 * database leak does not hand out live sessions (bible 14.2).
 */
@Component
public class TokenHasher {
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random;

    public TokenHasher(SecureRandom random) {
        this.random = random;
    }

    /** A 256-bit URL-safe random token, never logged or persisted in clear. */
    public String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }
}
