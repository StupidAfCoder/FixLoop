package com.fixloop.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemStorageTest {

    @Test
    void storesTheBytesUnderTheGeneratedKey(@TempDir Path directory) throws Exception {
        FilesystemStorage storage = new FilesystemStorage(new StorageProperties(directory.toString(), null));
        String key = "ab/cd/" + UUID.randomUUID() + ".png";
        byte[] content = AttachmentPolicyTest.png();

        storage.store(key, content);

        Path written = directory.resolve(key);
        assertThat(written).exists();
        assertThat(Files.readAllBytes(written)).isEqualTo(content);
    }

    @Test
    void refusesAKeyThatDidNotComeFromTheKeyGenerator(@TempDir Path directory) {
        FilesystemStorage storage = new FilesystemStorage(new StorageProperties(directory.toString(), null));

        assertThatThrownBy(() -> storage.store("../../etc/passwd", new byte[] {1}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store("ab/cd/not-a-uuid.png", new byte[] {1}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store("ab/cd/" + UUID.randomUUID() + ".exe", new byte[] {1}))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsTheRootDirectoryWhenItDoesNotExist(@TempDir Path directory) {
        Path nested = directory.resolve("attachments/2026");

        new FilesystemStorage(new StorageProperties(nested.toString(), null));

        assertThat(nested).isDirectory();
    }
}
