package com.fixloop.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Refresh tokens are stored as hashes, rotated on every use, and linked
 * through {@code replaced_by} so a replayed token can be traced (bible 8.9).
 */
@Repository
public class RefreshTokenRepository {
    private static final RowMapper<RefreshToken> MAPPER = (rs, rowNumber) -> {
        java.time.OffsetDateTime revokedAt = rs.getObject("revoked_at", java.time.OffsetDateTime.class);
        return new RefreshToken(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("expires_at", java.time.OffsetDateTime.class).toInstant(),
            revokedAt == null ? null : revokedAt.toInstant(),
            rs.getObject("replaced_by", UUID.class));
    };

    private final JdbcTemplate jdbc;

    public RefreshTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID create(UUID userId, String tokenHash, Instant expiresAt) {
        return jdbc.queryForObject(
            "INSERT INTO refresh_tokens (user_id, token_hash, expires_at) "
                + "VALUES (?, ?, ?) RETURNING id",
            UUID.class, userId, tokenHash, java.sql.Timestamp.from(expiresAt));
    }

    public Optional<RefreshToken> findByHash(String tokenHash) {
        List<RefreshToken> rows = jdbc.query(
            "SELECT id, user_id, expires_at, revoked_at, replaced_by FROM refresh_tokens "
                + "WHERE token_hash = ?",
            MAPPER, tokenHash);
        return rows.stream().findFirst();
    }

    public void revoke(UUID id, UUID replacedBy) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now(), replaced_by = ? "
            + "WHERE id = ? AND revoked_at IS NULL", replacedBy, id);
    }

    public void revokeAllForUser(UUID userId) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() "
            + "WHERE user_id = ? AND revoked_at IS NULL", userId);
    }

    public record RefreshToken(UUID id, UUID userId, Instant expiresAt,
                               Instant revokedAt, UUID replacedBy) {
    }
}
