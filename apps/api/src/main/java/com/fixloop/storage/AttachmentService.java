package com.fixloop.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fixloop.common.ApiException;

/**
 * Turns an uploaded photograph into a stored file.
 *
 * <p>The key is content-sharded ({@code ab/cd/uuid.jpg}) so a directory with
 * thousands of photographs stays navigable, which matters on the small volumes
 * a free hosting tier provides.
 *
 * <p>No database row is written here yet: the {@code attachments} table requires
 * a {@code complaint_id}, and complaint creation belongs to the backend track.
 * The complaint endpoint calls this service and inserts the row with the
 * returned key and checksum.
 */
@Service
public class AttachmentService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentPolicy policy;
    private final StorageBackend storage;

    public AttachmentService(AttachmentPolicy policy, StorageBackend storage) {
        this.policy = policy;
        this.storage = storage;
    }

    public StoredAttachment store(String declaredContentType, byte[] content, UUID uploaderId) {
        ImageType type = policy.inspect(declaredContentType, content);
        String checksum = sha256(content);
        String key = keyFor(checksum, type, UUID.randomUUID());

        try {
            storage.store(key, content);
        } catch (IllegalStateException exception) {
            log.error("Could not store an attachment for account {}", uploaderId, exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_UNAVAILABLE",
                "The photograph could not be saved. Please try again.");
        }

        log.info("Stored a {} photograph ({} bytes) for account {}", type.mimeType(), content.length, uploaderId);
        return new StoredAttachment(key, type.mimeType(), content.length, checksum);
    }

    static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is required of every Java platform.
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String keyFor(String checksum, ImageType type, UUID random) {
        return checksum.substring(0, 2) + "/" + checksum.substring(2, 4) + "/"
            + random + "." + type.extension();
    }
}
