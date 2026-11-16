package io.github.cnrozkn.s3client.model;

import java.time.Instant;

/** An object in a listing: name relative to the folder, full key, size and last change. */
public record FileEntry(String name, String key, long size, Instant lastModified) {}
