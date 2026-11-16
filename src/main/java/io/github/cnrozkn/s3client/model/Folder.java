package io.github.cnrozkn.s3client.model;

/** A folder or breadcrumb step: name relative to the parent and the full prefix ending in '/'. */
public record Folder(String name, String prefix) {}
