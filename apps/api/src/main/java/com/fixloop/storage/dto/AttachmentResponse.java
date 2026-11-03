package com.fixloop.storage.dto;

/** What a client gets back after a successful upload. */
public record AttachmentResponse(String storageKey, String mimeType, long sizeBytes, String checksumSha256) {
}
