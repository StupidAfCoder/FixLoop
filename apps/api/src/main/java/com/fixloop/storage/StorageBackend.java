package com.fixloop.storage;

/** Bytes in, nothing out: the storage implementation owns the naming. */
public interface StorageBackend {

    /**
     * Writes {@code content} under {@code key}.
     *
     * @throws IllegalArgumentException if the key does not have the generated shape
     * @throws IllegalStateException    if the bytes could not be written
     */
    void store(String key, byte[] content);
}
