package com.fixloop.storage;

/**
 * What the API knows about a stored file: where it went, what it is, how big it
 * is, and a checksum that lets anyone confirm the bytes later without trusting
 * the transport.
 */
public record StoredAttachment(String storageKey, String mimeType, long sizeBytes, String sha256) {
}
