package com.fixloop.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fixloop.common.ApiException;
import org.junit.jupiter.api.Test;

class AttachmentServiceTest {

    @Test
    void storesTheBytesAndReportsAChecksumThatMatchesTheKey() {
        RecordingStorage storage = new RecordingStorage(null);
        AttachmentService service = new AttachmentService(
            new AttachmentPolicy(new StorageProperties(null, null)), storage);

        StoredAttachment stored = service.store("image/png", AttachmentPolicyTest.png(), UUID.randomUUID());

        assertThat(stored.mimeType()).isEqualTo("image/png");
        assertThat(stored.sizeBytes()).isEqualTo(64);
        assertThat(stored.sha256()).matches("[0-9a-f]{64}");
        assertThat(stored.storageKey()).matches("[a-f0-9]{2}/[a-f0-9]{2}/[0-9a-f-]{36}\\.png");
        // The shard folders are the first bytes of the checksum, so a file can be
        // found from its checksum alone.
        assertThat(stored.storageKey().substring(0, 2)).isEqualTo(stored.sha256().substring(0, 2));
        assertThat(storage.keys).containsExactly(stored.storageKey());
        assertThat(storage.written).isEqualTo(AttachmentPolicyTest.png());
    }

    @Test
    void reportsAFriendlyFailureWhenTheDiskRefusesTheWrite() {
        RecordingStorage storage = new RecordingStorage(new IllegalStateException("disk full"));
        AttachmentService service = new AttachmentService(
            new AttachmentPolicy(new StorageProperties(null, null)), storage);

        assertThatThrownBy(() -> service.store("image/png", AttachmentPolicyTest.png(), UUID.randomUUID()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("could not be saved")
            .satisfies(exception -> assertThat(((ApiException) exception).code())
                .isEqualTo("STORAGE_UNAVAILABLE"));
    }

    @Test
    void doesNotTouchStorageWhenTheUploadIsRefused() {
        RecordingStorage storage = new RecordingStorage(null);
        AttachmentService service = new AttachmentService(
            new AttachmentPolicy(new StorageProperties(null, null)), storage);

        assertThatThrownBy(() -> service.store("application/pdf", "not a photo".getBytes(), UUID.randomUUID()))
            .isInstanceOf(ApiException.class);
        assertThat(storage.keys).isEmpty();
    }

    private static final class RecordingStorage implements StorageBackend {
        private final RuntimeException failure;
        private final List<String> keys = new ArrayList<>();
        private byte[] written;

        private RecordingStorage(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void store(String key, byte[] content) {
            if (failure != null) {
                throw failure;
            }
            keys.add(key);
            written = content;
        }
    }
}
