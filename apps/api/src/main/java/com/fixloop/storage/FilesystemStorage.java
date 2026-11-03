package com.fixloop.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stores attachment bytes under a configured directory.
 *
 * <p>Local development and the first deployment both use this. It is a
 * deliberate stop-gap: a shared object store is the right home for photographs
 * once the API runs on more than one instance, and the only thing that has to
 * change is the {@link StorageBackend} implementation.
 *
 * <p>Two rules keep a write from escaping the directory: a key must match the
 * generated shape exactly, and the resolved path must still sit under the root
 * after normalisation. Neither check trusts the caller.
 */
@Component
public class FilesystemStorage implements StorageBackend {
    private static final Logger log = LoggerFactory.getLogger(FilesystemStorage.class);

    /** the two shard folders, then a UUID, then an extension from ImageType. */
    private static final java.util.regex.Pattern KEY_SHAPE = java.util.regex.Pattern.compile(
        "[a-f0-9]{2}/[a-f0-9]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)");

    private final Path root;

    public FilesystemStorage(StorageProperties properties) {
        this.root = Paths.get(properties.root()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Cannot create the attachment directory " + root + ". Set ATTACHMENT_STORAGE_DIR to a writable path.",
                exception);
        }
        log.info("Attachment storage: {} (maximum {} bytes per file)", root, properties.maxBytes());
    }

    @Override
    public void store(String key, byte[] content) {
        if (key == null || !KEY_SHAPE.matcher(key).matches()) {
            throw new IllegalArgumentException("Refusing to store an attachment under an unexpected key");
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            // Cannot happen with the shape check above; kept so a future change
            // to the pattern cannot quietly introduce a traversal.
            throw new IllegalArgumentException("Refusing to store an attachment outside the storage directory");
        }

        try {
            Path parent = target.getParent();
            Files.createDirectories(parent);
            // Write beside the target and move it into place, so a crash leaves
            // no half-written photograph behind.
            Path temporary = Files.createTempFile(parent, "upload-", ".part");
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not store the attachment", exception);
        }
    }
}
