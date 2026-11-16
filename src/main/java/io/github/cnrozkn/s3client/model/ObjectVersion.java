package io.github.cnrozkn.s3client.model;

import java.time.Instant;

/** One version or delete marker of a key. */
public record ObjectVersion(String versionId, boolean latest, boolean deleteMarker, long size, Instant lastModified) {}
