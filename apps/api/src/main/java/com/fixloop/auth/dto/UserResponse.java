package com.fixloop.auth.dto;

import java.util.UUID;

import com.fixloop.auth.Role;
import com.fixloop.auth.UserAccount;

/** What a user may see about their own account. Never contains the password hash. */
public record UserResponse(
        UUID id,
        String email,
        String fullName,
        Role role,
        UUID departmentId,
        UUID scopeLocationId,
        boolean mustChangePassword) {

    public static UserResponse from(UserAccount user) {
        return new UserResponse(user.id(), user.email(), user.fullName(), user.role(),
            user.departmentId(), user.scopeLocationId(), user.mustChangePassword());
    }
}
