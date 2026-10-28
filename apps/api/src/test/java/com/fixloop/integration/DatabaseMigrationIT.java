package com.fixloop.integration;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs during mvn verify, against a fresh real PostgreSQL 16 container. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatabaseMigrationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate http;

    @Test
    void flywayAppliesTheExistingSchemaAndWorkflowTransitions() {
        assertThat(jdbc.queryForObject("SHOW server_version_num", Integer.class))
            .isBetween(160000, 169999);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM status_transitions", Integer.class))
            .isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.complaints')::text", String.class))
            .isEqualTo("complaints");
    }

    @Test
    void healthIncludesTheDatabaseAndDomainEndpointsStayClosed() {
        ResponseEntity<Map> health = http.getForEntity("/actuator/health", Map.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).containsEntry("status", "UP");
        assertThat(http.getForEntity("/api/v1/health", Map.class).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/v1/complaints", Map.class).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void generatedApiDocsAndSwaggerUiArePublic() {
        ResponseEntity<String> openApi = http.getForEntity("/v3/api-docs", String.class);
        assertThat(openApi.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(openApi.getBody()).contains("\"/api/v1/health\"");
        assertThat(http.getForEntity("/swagger-ui/index.html", String.class).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    }

    @Test
    void auditEventsRejectUpdatesAndDeletes() {
        UUID reporter = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID complaint = UUID.randomUUID();

        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, role) VALUES (?, ?, ?, ?, 'STUDENT')",
            reporter, reporter + "@example.test", "not-a-real-password-hash", "Test reporter");
        jdbc.update("INSERT INTO categories (id, name, theme, default_severity, photo_policy) VALUES (?, ?, 'OTHER', 'LOW', 'OPTIONAL')",
            category, "Test category " + category);
        jdbc.update("INSERT INTO locations (id, type, name, path) VALUES (?, 'CAMPUS', ?, ?)",
            location, "Test campus", "/test/" + location + "/");
        jdbc.update("INSERT INTO complaints (id, code, reporter_id, category_id, location_id, title, severity, status) "
                + "VALUES (?, ?, ?, ?, ?, 'Test complaint', 'LOW', 'REPORTED')",
            complaint, "FL-TEST-" + complaint, reporter, category, location);
        Long eventId = jdbc.queryForObject("INSERT INTO complaint_events (complaint_id, actor_id, actor_role, event_type) "
                + "VALUES (?, ?, 'STUDENT', 'CREATED') RETURNING id", Long.class, complaint, reporter);

        assertThatThrownBy(() -> jdbc.update("UPDATE complaint_events SET payload = '{}'::jsonb WHERE id = ?", eventId))
            .isInstanceOf(DataAccessException.class)
            .rootCause().hasMessageContaining("complaint_events is append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM complaint_events WHERE id = ?", eventId))
            .isInstanceOf(DataAccessException.class)
            .rootCause().hasMessageContaining("complaint_events is append-only");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM complaint_events WHERE id = ?", Integer.class, eventId))
            .isEqualTo(1);
    }
}
