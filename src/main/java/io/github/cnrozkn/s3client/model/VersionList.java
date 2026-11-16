package io.github.cnrozkn.s3client.model;

import java.util.List;

/** Versions of a key, newest first; truncated means older versions exist beyond the list. */
public record VersionList(List<ObjectVersion> versions, boolean truncated) {}
