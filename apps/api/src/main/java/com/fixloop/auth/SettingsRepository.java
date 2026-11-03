package com.fixloop.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Configuration that an administrator may change without a deployment
 * (bible 8.1: rules live in data). Only keys this module needs are read here.
 */
@Repository
public class SettingsRepository {
    private final JdbcTemplate jdbc;

    public SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findValue(String key) {
        List<String> rows = jdbc.queryForList(
            "SELECT value FROM settings WHERE key = ?", String.class, key);
        return rows.stream().findFirst();
    }
}
