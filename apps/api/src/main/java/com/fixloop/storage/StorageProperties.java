package com.fixloop.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where attachment bytes live and how large one may be.
 *
 * <p>{@code root} defaults to a directory under the system temporary directory
 * so a fresh checkout works without configuration; a deployment must set
 * {@code ATTACHMENT_STORAGE_DIR} to a path on a persistent volume, because a
 * container's temporary directory disappears with the container.
 */
@ConfigurationProperties("fixloop.storage")
public record StorageProperties(String root, Long maxBytes) {

    public StorageProperties {
        String resolvedRoot = root == null || root.isBlank()
            ? System.getProperty("java.io.tmpdir") + "/fixloop-attachments"
            : root;
        root = resolvedRoot;
        maxBytes = maxBytes == null || maxBytes <= 0 ? 8L * 1024 * 1024 : maxBytes;
    }
}
