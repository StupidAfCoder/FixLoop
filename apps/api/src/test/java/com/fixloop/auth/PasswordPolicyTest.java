package com.fixloop.auth;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsAReasonablePassword() {
        policy.validate("campus-lamp-2026", "student@bmu.edu.in");
    }

    @Test
    void rejectsShortPasswords() {
        assertThatThrownBy(() -> policy.validate("short", "student@bmu.edu.in"))
            .isInstanceOf(com.fixloop.common.ApiException.class)
            .hasMessageContaining("at least 12 characters");
    }

    @Test
    void rejectsTheEmailAddressOrItsLocalPart() {
        assertThatThrownBy(() -> policy.validate("student@bmu.edu.in", "student@bmu.edu.in"))
            .hasMessageContaining("email address");
        assertThatThrownBy(() -> policy.validate("student-student", "student-student@bmu.edu.in"))
            .hasMessageContaining("email address");
    }

    @Test
    void rejectsAnObviousPassword() {
        assertThatThrownBy(() -> policy.validate("password1234", "student@bmu.edu.in"))
            .hasMessageContaining("too easy");
    }

    @Test
    void rejectsMoreThanBcryptCanUse() {
        String tooLong = "a".repeat(73);
        assertThatThrownBy(() -> policy.validate(tooLong, "student@bmu.edu.in"))
            .hasMessageContaining("72 bytes");
    }

    @Test
    void tokensAreRandomAndOnlyStoredAsHashes() {
        TokenHasher hasher = new TokenHasher(new SecureRandom());
        String first = hasher.newToken();
        String second = hasher.newToken();

        assertThat(first).isNotEqualTo(second).hasSize(43);
        assertThat(hasher.hash(first)).hasSize(64).isNotEqualTo(first);
        assertThat(hasher.hash(first)).isEqualTo(hasher.hash(first));
    }

    @Test
    void tokenHasherProducesDistinctHashesForDistinctTokens() {
        TokenHasher hasher = new TokenHasher(new SecureRandom());
        assertThat(hasher.hash(hasher.newToken())).isNotEqualTo(hasher.hash(hasher.newToken()));
    }
}
