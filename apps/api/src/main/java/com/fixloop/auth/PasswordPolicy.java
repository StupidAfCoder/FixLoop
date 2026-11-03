package com.fixloop.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import com.fixloop.common.ApiException;
import org.springframework.stereotype.Component;

/**
 * Password rules from bible 14.2: minimum length, never the email address,
 * and a 72-byte ceiling because BCrypt silently ignores anything beyond it.
 */
@Component
public class PasswordPolicy {
    public static final int MINIMUM_LENGTH = 12;
    private static final int BCRYPT_MAX_BYTES = 72;

    private static final Set<String> TOO_COMMON = Set.of(
        "password1234", "password12345", "123456789012", "qwertyuiop12",
        "letmein12345", "fixloop12345", "admin1234567");

    public void validate(String password, String email) {
        if (password == null || password.length() < MINIMUM_LENGTH) {
            throw ApiException.badRequest("WEAK_PASSWORD",
                "Use at least " + MINIMUM_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw ApiException.badRequest("PASSWORD_TOO_LONG",
                "Use a password shorter than " + BCRYPT_MAX_BYTES + " bytes.");
        }
        String normalised = password.toLowerCase(Locale.ROOT);
        if (TOO_COMMON.contains(normalised)) {
            throw ApiException.badRequest("WEAK_PASSWORD", "That password is too easy to guess.");
        }
        String address = email == null ? "" : email.toLowerCase(Locale.ROOT);
        String localPart = address.contains("@") ? address.substring(0, address.indexOf('@')) : address;
        if (!address.isEmpty() && (normalised.equals(address) || normalised.equals(localPart))) {
            throw ApiException.badRequest("WEAK_PASSWORD", "Do not use your email address as the password.");
        }
    }
}
