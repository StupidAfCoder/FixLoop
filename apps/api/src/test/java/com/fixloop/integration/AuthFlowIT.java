package com.fixloop.integration;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The identity flows from the bible (FR-101 to FR-104, FR-108) against a real
 * PostgreSQL 16 instance: register, log in, use the token, rotate the refresh
 * token, detect replay, and log out.
 */
@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        // Rate limiting is unit tested; switching it off keeps these flows deterministic.
        "fixloop.auth.rate-limit-enabled=false",
        "fixloop.auth.allowed-email-domains=bmu.edu.in"
    })
class AuthFlowIT {
    private static final String PASSWORD = "campus-lamp-2026";
    private static final String NEW_PASSWORD = "library-window-2027";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The container has no TLS and starts empty, unlike the hosted database.
        registry.add("spring.datasource.hikari.data-source-properties.sslmode", () -> "disable");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private TestRestTemplate http;

    @Test
    void aStudentCanRegisterLogInRefreshAndLogOut() {
        String email = newEmail();

        ResponseEntity<Map> registered = register(email, PASSWORD, "Test Student");
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getBody()).containsEntry("email", email)
            .containsEntry("role", "STUDENT");
        assertThat(registered.getBody()).doesNotContainKey("passwordHash");
        assertThat(registered.getBody()).doesNotContainKey("password");

        ResponseEntity<Map> session = login(email, PASSWORD);
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(session.getBody()).containsEntry("tokenType", "Bearer");
        String accessToken = (String) session.getBody().get("accessToken");
        String refreshToken = (String) session.getBody().get("refreshToken");
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();
        assertThat(session.getBody().get("expiresInSeconds")).isEqualTo(900);

        ResponseEntity<Map> me = me(accessToken);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsEntry("email", email).containsEntry("role", "STUDENT");

        // The token is required, and a forged one is refused.
        assertThat(http.getForEntity("/api/v1/me", Map.class).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(me("not-a-real-token").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Refresh rotates: the new token differs and the old one stops working.
        ResponseEntity<Map> refreshed = refresh(refreshToken);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String rotatedToken = (String) refreshed.getBody().get("refreshToken");
        assertThat(rotatedToken).isNotEqualTo(refreshToken);

        // Presenting the rotated-away token again is treated as theft: the whole
        // family is revoked, including the token that was just issued.
        assertThat(refresh(refreshToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(rotatedToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // A fresh session can be ended explicitly.
        String freshRefreshToken = (String) login(email, PASSWORD).getBody().get("refreshToken");
        assertThat(logout(freshRefreshToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refresh(freshRefreshToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void registrationRefusesBadDomainsWeakPasswordsAndDuplicates() {
        ResponseEntity<Map> wrongDomain = register("someone@gmail.com", PASSWORD, "Test Student");
        assertThat(wrongDomain.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(wrongDomain.getBody()).containsEntry("code", "EMAIL_DOMAIN_NOT_ALLOWED");

        String email = newEmail();
        ResponseEntity<Map> weakPassword = register(email, "short", "Test Student");
        assertThat(weakPassword.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(weakPassword.getBody()).containsEntry("code", "VALIDATION_FAILED");

        assertThat(register(email, PASSWORD, "Test Student").getStatusCode())
            .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> duplicate = register(email, PASSWORD, "Test Student");
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).containsEntry("code", "EMAIL_ALREADY_REGISTERED");

        ResponseEntity<Map> missingFields = register("", PASSWORD, "");
        assertThat(missingFields.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingFields.getBody()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(missingFields.getBody()).containsKey("details");
    }

    @Test
    void loginFailuresAreIndistinguishableAndTokensCarryTheirRole() {
        String email = newEmail();
        register(email, PASSWORD, "Test Student");

        ResponseEntity<Map> unknownEmail = login("nobody-" + UUID.randomUUID() + "@bmu.edu.in", PASSWORD);
        ResponseEntity<Map> wrongPassword = login(email, "another-wrong-password");
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getBody()).containsEntry("code", "INVALID_CREDENTIALS");
        assertThat(wrongPassword.getBody()).containsEntry("code", "INVALID_CREDENTIALS");
        assertThat(unknownEmail.getBody().get("message")).isEqualTo(wrongPassword.getBody().get("message"));

        String accessToken = (String) login(email, PASSWORD).getBody().get("accessToken");
        // A student holds a valid token but no domain endpoint opens for them yet.
        ResponseEntity<Map> forbidden = exchange("/api/v1/complaints", accessToken);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody()).containsEntry("code", "FORBIDDEN");
    }

    @Test
    void changingThePasswordRetiresTheOldPasswordAndAllSessions() {
        String email = newEmail();
        register(email, PASSWORD, "Test Student");
        String accessToken = (String) login(email, PASSWORD).getBody().get("accessToken");
        String refreshToken = (String) login(email, PASSWORD).getBody().get("refreshToken");

        ResponseEntity<Map> wrongCurrent = post("/api/v1/auth/change-password", accessToken,
            Map.of("currentPassword", "not-the-password", "newPassword", NEW_PASSWORD));
        assertThat(wrongCurrent.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(wrongCurrent.getBody()).containsEntry("code", "INVALID_CURRENT_PASSWORD");

        assertThat(post("/api/v1/auth/change-password", accessToken,
            Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD)).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh(refreshToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static String newEmail() {
        return "student-" + UUID.randomUUID() + "@bmu.edu.in";
    }

    private ResponseEntity<Map> register(String email, String password, String fullName) {
        return http.postForEntity("/api/v1/auth/register",
            Map.of("email", email, "password", password, "fullName", fullName), Map.class);
    }

    private ResponseEntity<Map> login(String email, String password) {
        return http.postForEntity("/api/v1/auth/login",
            Map.of("email", email, "password", password), Map.class);
    }

    private ResponseEntity<Map> refresh(String refreshToken) {
        return http.postForEntity("/api/v1/auth/refresh",
            Map.of("refreshToken", refreshToken), Map.class);
    }

    private ResponseEntity<Map> logout(String refreshToken) {
        return http.postForEntity("/api/v1/auth/logout",
            Map.of("refreshToken", refreshToken), Map.class);
    }

    private ResponseEntity<Map> me(String accessToken) {
        return exchange("/api/v1/me", accessToken);
    }

    private ResponseEntity<Map> post(String path, String accessToken, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> exchange(String path, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }
}
