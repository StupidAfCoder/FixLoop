package com.fixloop.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Reads and writes the {@code users} table with plain SQL (bible 7.1). */
@Repository
public class UserRepository {
    private static final String COLUMNS = "id, email, password_hash, full_name, role, department_id, "
        + "scope_location_id, email_verified, must_change_password, active";

    private static final RowMapper<UserAccount> MAPPER = UserRepository::mapRow;

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByEmail(String email) {
        List<UserAccount> rows = jdbc.query(
            "SELECT " + COLUMNS + " FROM users WHERE email = ?", MAPPER, email);
        return rows.stream().findFirst();
    }

    public Optional<UserAccount> findById(UUID id) {
        List<UserAccount> rows = jdbc.query(
            "SELECT " + COLUMNS + " FROM users WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public boolean emailExists(String email) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
        return count != null && count > 0;
    }

    /** Students are created only through registration; staff accounts come from an admin. */
    public UserAccount insertStudent(String email, String passwordHash, String fullName) {
        UUID id = jdbc.queryForObject(
            "INSERT INTO users (email, password_hash, full_name, role) VALUES (?, ?, ?, 'STUDENT') "
                + "RETURNING id",
            UUID.class, email, passwordHash, fullName);
        return new UserAccount(id, email, passwordHash, fullName, Role.STUDENT,
            null, null, false, false, true);
    }

    public void recordLogin(UUID id) {
        jdbc.update("UPDATE users SET last_login_at = now() WHERE id = ?", id);
    }

    public void updatePasswordHash(UUID id, String passwordHash, boolean mustChangePassword) {
        jdbc.update("UPDATE users SET password_hash = ?, must_change_password = ? WHERE id = ?",
            passwordHash, mustChangePassword, id);
    }

    private static UserAccount mapRow(ResultSet rs, int rowNumber) throws SQLException {
        return new UserAccount(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("full_name"),
            Role.valueOf(rs.getString("role")),
            rs.getObject("department_id", UUID.class),
            rs.getObject("scope_location_id", UUID.class),
            rs.getBoolean("email_verified"),
            rs.getBoolean("must_change_password"),
            rs.getBoolean("active"));
    }
}
