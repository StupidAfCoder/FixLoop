package com.fixloop.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fixloop.common.ApiException;
import org.junit.jupiter.api.Test;

class AttachmentPolicyTest {
    private final AttachmentPolicy policy = new AttachmentPolicy(new StorageProperties(null, null));

    static byte[] jpeg() {
        byte[] content = new byte[64];
        content[0] = (byte) 0xFF;
        content[1] = (byte) 0xD8;
        content[2] = (byte) 0xFF;
        content[3] = (byte) 0xE0;
        return content;
    }

    static byte[] png() {
        byte[] content = new byte[64];
        content[0] = (byte) 0x89;
        content[1] = 'P';
        content[2] = 'N';
        content[3] = 'G';
        content[4] = 0x0D;
        content[5] = 0x0A;
        content[6] = 0x1A;
        content[7] = 0x0A;
        return content;
    }

    static byte[] webp() {
        byte[] content = new byte[64];
        content[0] = 'R';
        content[1] = 'I';
        content[2] = 'F';
        content[3] = 'F';
        content[8] = 'W';
        content[9] = 'E';
        content[10] = 'B';
        content[11] = 'P';
        return content;
    }

    @Test
    void acceptsTheThreePhotographFormatsItAdvertises() {
        assertThat(policy.inspect("image/jpeg", jpeg())).isEqualTo(ImageType.JPEG);
        assertThat(policy.inspect("image/png", png())).isEqualTo(ImageType.PNG);
        assertThat(policy.inspect("image/webp", webp())).isEqualTo(ImageType.WEBP);
    }

    @Test
    void trustsTheContentRatherThanTheDeclaredType() {
        // A client that sends PNG bytes labelled octet-stream still gets through:
        // the bytes are a real image, which is the property that matters.
        assertThat(policy.inspect("application/octet-stream", png())).isEqualTo(ImageType.PNG);
        assertThat(policy.inspect(null, jpeg())).isEqualTo(ImageType.JPEG);
    }

    @Test
    void refusesSomethingThatIsNotAPhotograph() {
        byte[] pdf = "%PDF-1.7 not-an-image-at-all".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> policy.inspect("application/pdf", pdf))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("JPEG")
            .satisfies(exception -> assertThat(((ApiException) exception).code())
                .isEqualTo("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    void refusesAFileWhoseContentContradictsItsName() {
        assertThatThrownBy(() -> policy.inspect("image/png", jpeg()))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> assertThat(((ApiException) exception).code())
                .isEqualTo("FILE_TYPE_MISMATCH"));
    }

    @Test
    void refusesAnEmptyUpload() {
        assertThatThrownBy(() -> policy.inspect("image/jpeg", new byte[0]))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> assertThat(((ApiException) exception).code())
                .isEqualTo("EMPTY_FILE"));
    }

    @Test
    void refusesAPhotographOverTheConfiguredLimit() {
        AttachmentPolicy tiny = new AttachmentPolicy(new StorageProperties(null, 32L));

        assertThatThrownBy(() -> tiny.inspect("image/jpeg", jpeg()))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> assertThat(((ApiException) exception).code())
                .isEqualTo("FILE_TOO_LARGE"));
    }
}
