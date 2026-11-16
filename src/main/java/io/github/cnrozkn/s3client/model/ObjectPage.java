package io.github.cnrozkn.s3client.model;

import java.util.List;

/**
 * One listing page.
 *
 * @param nextToken where the next page starts, or null on the last page
 * @param foldersPartial the folders-first search on the first page stopped early; later folders appear in order
 * @param paused the page hit its request or time budget and holds fewer entries than a full page
 */
public record ObjectPage(
        List<Folder> folders, List<FileEntry> files, String nextToken, boolean foldersPartial, boolean paused) {}
