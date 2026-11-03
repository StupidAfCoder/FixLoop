package com.fixloop.storage;

/**
 * The photograph formats the API accepts.
 *
 * <p>Only formats that can be recognised from their first bytes are allowed.
 * The name a client gives a file proves nothing, so the content decides what it
 * is (see {@link AttachmentPolicy}). HEIC is excluded on purpose: it needs a
 * library to inspect and the Android camera app is configured to produce JPEG.
 */
public enum ImageType {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    private final String mimeType;
    private final String extension;

    ImageType(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    /** Recognises the format from the leading bytes, or returns null. */
    public static ImageType detect(byte[] content) {
        if (content == null || content.length < 12) {
            return null;
        }
        if ((content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8 && (content[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        if ((content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N' && content[3] == 'G'
                && (content[4] & 0xFF) == 0x0D && (content[5] & 0xFF) == 0x0A
                && (content[6] & 0xFF) == 0x1A && (content[7] & 0xFF) == 0x0A) {
            return PNG;
        }
        if (content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return WEBP;
        }
        return null;
    }

    /** The type matching a declared content type, used when comparing the two. */
    public static ImageType fromMimeType(String declared) {
        if (declared == null) {
            return null;
        }
        String normalised = declared.trim().toLowerCase(java.util.Locale.ROOT);
        int parameters = normalised.indexOf(';');
        if (parameters >= 0) {
            normalised = normalised.substring(0, parameters).trim();
        }
        for (ImageType type : values()) {
            if (type.mimeType.equals(normalised)) {
                return type;
            }
        }
        if ("image/jpg".equals(normalised) || "image/pjpeg".equals(normalised)) {
            return JPEG;
        }
        return null;
    }
}
