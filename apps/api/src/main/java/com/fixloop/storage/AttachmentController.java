package com.fixloop.storage;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import com.fixloop.common.ApiException;
import com.fixloop.common.RequestRateLimiter;
import com.fixloop.config.AuthProperties;
import com.fixloop.storage.dto.AttachmentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload endpoint for complaint photographs.
 *
 * <p>There is deliberately no download endpoint yet. Reading a photograph has to
 * be authorised against the complaint it belongs to, and complaints do not exist
 * in the API yet; a read endpoint now would let any signed-in student fetch
 * another student's photograph by guessing a key. Reading arrives with the
 * complaint endpoints, where the ownership rule lives.
 */
@RestController
public class AttachmentController {
    private static final Logger log = LoggerFactory.getLogger(AttachmentController.class);

    /** Enough for a complaint wizard with several photographs, not enough to fill a volume. */
    private static final int UPLOAD_LIMIT = 30;
    private static final Duration UPLOAD_WINDOW = Duration.ofMinutes(5);

    private final AttachmentService attachments;
    private final RequestRateLimiter rateLimiter;
    private final AuthProperties properties;

    public AttachmentController(AttachmentService attachments, RequestRateLimiter rateLimiter,
                                AuthProperties properties) {
        this.attachments = attachments;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @PostMapping(path = "/api/v1/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file) {
        UUID uploaderId = toUserId(jwt);
        limit(uploaderId);

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            log.warn("Could not read an upload from account {}", uploaderId, exception);
            throw ApiException.badRequest("MALFORMED_REQUEST", "The upload could not be read.");
        }

        StoredAttachment stored = attachments.store(file.getContentType(), content, uploaderId);
        return new AttachmentResponse(stored.storageKey(), stored.mimeType(), stored.sizeBytes(), stored.sha256());
    }

    private void limit(UUID userId) {
        if (properties.rateLimitEnabled()) {
            // Keyed by account, not by address: one student on a shared campus
            // network must not be able to exhaust another's allowance.
            rateLimiter.check("attachment:" + userId, UPLOAD_LIMIT, UPLOAD_WINDOW);
        }
    }

    private static UUID toUserId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw ApiException.unauthenticated("Your session is no longer valid. Please sign in again.");
        }
    }
}
