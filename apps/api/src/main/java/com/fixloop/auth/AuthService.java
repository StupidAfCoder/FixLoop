package com.fixloop.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.fixloop.auth.dto.TokenResponse;
import com.fixloop.auth.dto.UserResponse;
import com.fixloop.common.ApiException;
import com.fixloop.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login, refresh rotation, logout and password change
 * (FR-101 to FR-104, FR-108; bible 8.9 and 14.2).
 *
 * <p>Every failure a client can see is an {@link ApiException}. Login failures
 * are deliberately indistinguishable: the same status, code and message for an
 * unknown address, a wrong password and a disabled account.
 */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String SETTING_ALLOWED_DOMAINS = "allowed_email_domains";
    private static final String SETTING_OPEN_SIGNUP = "demo_mode_open_signup";
    private static final String SESSION_EXPIRED = "Your session has expired. Please sign in again.";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final SettingsRepository settings;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final TokenHasher tokenHasher;
    private final JwtService jwtService;
    private final AuthProperties properties;
    private final Clock clock;
    /** Compared against when no user exists, so a failed login costs the same time either way. */
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens,
                       SettingsRepository settings, PasswordEncoder passwordEncoder,
                       PasswordPolicy passwordPolicy, TokenHasher tokenHasher,
                       JwtService jwtService, AuthProperties properties, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.settings = settings;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.tokenHasher = tokenHasher;
        this.jwtService = jwtService;
        this.properties = properties;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(tokenHasher.newToken());
    }

    @Transactional
    public UserResponse register(String rawEmail, String password, String rawFullName) {
        if (!properties.registrationEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REGISTRATION_DISABLED",
                "Registration is closed. Ask an administrator for an account.");
        }
        String email = normaliseEmail(rawEmail);
        String fullName = normaliseName(rawFullName);
        requireAllowedDomain(email);
        passwordPolicy.validate(password, email);
        if (users.emailExists(email)) {
            // Registration necessarily reveals that an address is taken. That is
            // accepted for a campus tool; it is recorded in AI_USAGE.md.
            throw ApiException.conflict("EMAIL_ALREADY_REGISTERED",
                "An account already exists for this address. Try signing in instead.");
        }
        UserAccount created = users.insertStudent(email, passwordEncoder.encode(password), fullName);
        log.info("Registered student account {}", created.id());
        return UserResponse.from(created);
    }

    @Transactional
    public TokenResponse login(String rawEmail, String password) {
        String email = normaliseEmail(rawEmail);
        Optional<UserAccount> existing = users.findByEmail(email);
        String hash = existing.map(UserAccount::passwordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(password, hash);
        if (existing.isEmpty() || !passwordMatches || !existing.get().active()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "Email or password is incorrect.");
        }
        UserAccount user = existing.get();
        users.recordLogin(user.id());
        return issueTokens(user);
    }

    /**
     * Rotates the refresh token. A token that was already rotated and is
     * presented again is treated as stolen: the whole session family is
     * revoked. The write must survive the exception, hence {@code noRollbackFor}.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(String presentedToken) {
        RefreshTokenRepository.RefreshToken stored = refreshTokens
            .findByHash(tokenHasher.hash(presentedToken))
            .orElseThrow(() -> ApiException.unauthenticated(SESSION_EXPIRED));

        if (stored.revokedAt() != null) {
            if (stored.replacedBy() != null) {
                log.warn("Refresh token reuse detected for user {}; revoking all sessions",
                    stored.userId());
                refreshTokens.revokeAllForUser(stored.userId());
            }
            throw ApiException.unauthenticated(SESSION_EXPIRED);
        }
        if (!stored.expiresAt().isAfter(clock.instant())) {
            refreshTokens.revoke(stored.id(), null);
            throw ApiException.unauthenticated(SESSION_EXPIRED);
        }

        UserAccount user = users.findById(stored.userId())
            .filter(UserAccount::active)
            .orElseThrow(() -> ApiException.unauthenticated(SESSION_EXPIRED));

        IssuedRefreshToken replacement = issueRefreshToken(user);
        refreshTokens.revoke(stored.id(), replacement.id());
        return TokenResponse.bearer(jwtService.issue(user).value(),
            properties.accessTokenTtl().toSeconds(), replacement.plainText());
    }

    @Transactional
    public void logout(String presentedToken) {
        refreshTokens.findByHash(tokenHasher.hash(presentedToken))
            .filter(token -> token.revokedAt() == null)
            .ifPresent(token -> refreshTokens.revoke(token.id(), null));
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        UserAccount user = users.findById(userId)
            .filter(UserAccount::active)
            .orElseThrow(() -> ApiException.unauthenticated(SESSION_EXPIRED));
        if (!passwordEncoder.matches(currentPassword, user.passwordHash())) {
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD", "The current password is incorrect.");
        }
        if (currentPassword.equals(newPassword)) {
            throw ApiException.badRequest("PASSWORD_UNCHANGED", "Choose a password you have not used here.");
        }
        passwordPolicy.validate(newPassword, user.email());
        users.updatePasswordHash(user.id(), passwordEncoder.encode(newPassword), false);
        // Any other device's session must not survive a password change.
        refreshTokens.revokeAllForUser(user.id());
        log.info("Password changed for user {}", user.id());
    }

    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return users.findById(userId)
            .filter(UserAccount::active)
            .map(UserResponse::from)
            .orElseThrow(() -> ApiException.unauthenticated(SESSION_EXPIRED));
    }

    private TokenResponse issueTokens(UserAccount user) {
        IssuedRefreshToken refresh = issueRefreshToken(user);
        return TokenResponse.bearer(jwtService.issue(user).value(),
            properties.accessTokenTtl().toSeconds(), refresh.plainText());
    }

    private IssuedRefreshToken issueRefreshToken(UserAccount user) {
        String plainText = tokenHasher.newToken();
        Instant expiresAt = clock.instant().plus(properties.refreshTokenTtl());
        UUID id = refreshTokens.create(user.id(), tokenHasher.hash(plainText), expiresAt);
        return new IssuedRefreshToken(id, plainText);
    }

    private void requireAllowedDomain(String email) {
        if (isOpenSignupEnabled()) {
            return;
        }
        String domain = domainOf(email);
        List<String> allowed = allowedDomains();
        if (allowed.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REGISTRATION_DISABLED",
                "No email domain is allowed to register yet. Ask an administrator.");
        }
        boolean permitted = allowed.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(domain));
        if (!permitted) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMAIL_DOMAIN_NOT_ALLOWED",
                "Register with a college email address (" + String.join(", ", allowed) + ").");
        }
    }

    /** The settings table wins; the deployment property is the fallback. */
    private List<String> allowedDomains() {
        String configured = settings.findValue(SETTING_ALLOWED_DOMAINS).orElse(null);
        if (configured == null || configured.isBlank()) {
            return properties.allowedEmailDomains();
        }
        return Arrays.stream(configured.split("[,;\\s]+"))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(value -> value.startsWith("@") ? value.substring(1) : value)
            .toList();
    }

    private boolean isOpenSignupEnabled() {
        return settings.findValue(SETTING_OPEN_SIGNUP)
            .map(value -> value.trim().equalsIgnoreCase("true"))
            .orElse(false);
    }

    static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normaliseName(String fullName) {
        String collapsed = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
        if (collapsed.length() < 2) {
            throw ApiException.badRequest("VALIDATION_FAILED", "A name is required.");
        }
        return collapsed;
    }

    private static String domainOf(String email) {
        int at = email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1);
    }

    private record IssuedRefreshToken(UUID id, String plainText) {
    }
}
