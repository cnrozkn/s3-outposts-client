package io.github.cnrozkn.s3client.model;

import java.time.Instant;
import java.util.Map;

/** Object headers and user metadata. */
public record ObjectDetail(
        String key, long size, String contentType, String eTag, Instant lastModified, Map<String, String> metadata) {}
