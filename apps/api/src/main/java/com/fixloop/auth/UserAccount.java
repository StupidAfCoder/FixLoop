package com.fixloop.auth;

import java.util.UUID;

/**
 * The authentication view of a user. Deliberately not a persistence entity:
 * no response object is built directly from it, so the password hash cannot
 * leak by accident.
 */
public record UserAccount(
        UUID id,
        String email,
        String passwordHash,
        String fullName,
        Role role,
        UUID departmentId,
        UUID scopeLocationId,
        boolean emailVerified,
        boolean mustChangePassword,
        boolean active) {
}
