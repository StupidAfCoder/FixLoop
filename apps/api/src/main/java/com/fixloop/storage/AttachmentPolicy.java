package com.fixloop.storage;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fixloop.common.ApiException;

/**
 * Decides whether an uploaded file is a photograph this system will keep.
 *
 * <p>The rule is content-first: the bytes are inspected and must be a JPEG, PNG
 * or WebP. A declared content type is only used to catch a mismatch, because a
 * client can declare anything. This matters for a complaint system — the files
 * that get stored are later shown to staff, so a mis-typed upload is a stored
 * cross-site-scripting attempt waiting for a reader.
 */
@Component
public class AttachmentPolicy {
    private final long maxBytes;

    public AttachmentPolicy(StorageProperties properties) {
        this.maxBytes = properties.maxBytes();
    }

    /**
     * @param declaredContentType what the client claimed, may be missing
     * @param content             the bytes as received
     * @return the detected image type
     * @throws ApiException 400 for an empty upload, 413 when too large,
     *                      415 when the content is not an accepted photograph
     */
    public ImageType inspect(String declaredContentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("EMPTY_FILE", "The uploaded file is empty.");
        }
        if (content.length > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                "That photo is larger than " + megabyteDescription() + ".");
        }

        ImageType detected = ImageType.detect(content);
        if (detected == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
                "Only JPEG, PNG and WebP photographs are accepted.");
        }

        ImageType declared = ImageType.fromMimeType(declaredContentType);
        if (declared != null && declared != detected) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FILE_TYPE_MISMATCH",
                "The file was declared as " + declared.mimeType()
                    + " but its content is " + detected.mimeType() + ".");
        }
        return detected;
    }

    private String megabyteDescription() {
        long megabytes = maxBytes / (1024L * 1024L);
        return megabytes >= 1 ? megabytes + " MB" : maxBytes + " bytes";
    }
}
