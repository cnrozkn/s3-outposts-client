package io.github.cnrozkn.s3client.storage;

import io.github.cnrozkn.s3client.model.FileEntry;
import io.github.cnrozkn.s3client.model.Folder;
import io.github.cnrozkn.s3client.model.ObjectPage;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Lists one page of a folder without the S3 delimiter.
 *
 * <p>With a delimiter, S3 on Outposts walks every key under each common prefix, so a large folder can take minutes.
 * Keys are read in order instead; when a key opens a subfolder, the folder is reported once and the next request
 * jumps past it with {@code StartAfter}.
 *
 * <p>Each page may make {@value #MAX_REQUESTS} requests within {@link #MAX_TIME}. When the budget runs out the page
 * comes back early with a token that continues where the scan stopped, so one slow folder never stalls the UI.
 */
final class KeyScan {

    static final int BATCH_SIZE = 1000;

    /** Batch size after a folder jump: a folder that outgrew a whole batch is often followed by another large one. */
    static final int PROBE_SIZE = 100;

    static final int MAX_REQUESTS = 10;

    static final Duration MAX_TIME = Duration.ofSeconds(3);

    /** Keys the first page reads past its last file while looking for more folders to put on top. */
    static final int FOLDER_SEARCH_KEYS = 2000;

    /** Sorts after every key that starts with a given prefix. */
    static final String AFTER_ALL = new String(Character.toChars(Character.MAX_CODE_POINT));

    /** A page and what it cost. */
    record Listed(ObjectPage page, int requests, int keys) {}

    private record Entry(S3Object object, String folder) {}

    private final S3Client s3;
    private final String alias;
    private final String prefix;
    private final int probeSize;
    private final InstantSource clock;
    private final Instant deadline;

    /** StartAfter of the next request, which is also the position just after the last entry handed out. */
    private String cursor;

    private List<S3Object> batch = List.of();
    private int index;
    private boolean truncated = true;
    private String skipping;
    private int requests;
    private int keys;
    private boolean outOfBudget;

    private KeyScan(S3Client s3, String alias, String prefix, String after, int probeSize, InstantSource clock) {
        this.s3 = s3;
        this.alias = alias;
        this.prefix = prefix;
        this.cursor = after;
        this.probeSize = probeSize;
        this.clock = clock;
        this.deadline = clock.instant().plus(MAX_TIME);
    }

    static Listed list(
            S3Client s3,
            String alias,
            String folder,
            String search,
            String token,
            int pageSize,
            int probeSize,
            InstantSource clock) {
        PageToken parsed = PageToken.parse(token);
        KeyScan scan =
                new KeyScan(s3, alias, folder + search, parsed == null ? null : parsed.after(), probeSize, clock);
        ObjectPage page = parsed == null ? scan.firstPage(folder, pageSize) : scan.nextPage(folder, parsed, pageSize);
        return new Listed(page, scan.requests, scan.keys);
    }

    /** Compares keys the way S3 orders them: by UTF-8 bytes, which is code point order. */
    static int compareKeys(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int left = a.codePointAt(i);
            int right = b.codePointAt(j);
            if (left != right) {
                return Integer.compare(left, right);
            }
            i += Character.charCount(left);
            j += Character.charCount(right);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /**
     * Folders first: after the page has its files, keeps reading up to {@value #FOLDER_SEARCH_KEYS} keys to put
     * later folders on top as well. If that search stops early, the next pages list the rest in place and skip the
     * folders shown here.
     */
    private ObjectPage firstPage(String folder, int pageSize) {
        List<Folder> folders = new ArrayList<>();
        List<FileEntry> files = new ArrayList<>();
        String lastFile = null;
        boolean moreFiles = false;
        while (true) {
            boolean full = files.size() == pageSize;
            if (full && folderSearchSpent()) {
                return new ObjectPage(folders, files, inPlaceToken(lastFile, cursor), true, false);
            }
            Entry entry = next();
            if (entry == null) {
                if (!outOfBudget) {
                    String token = moreFiles ? PageToken.filesOnly(lastFile).encode() : null;
                    return new ObjectPage(folders, files, token, false, false);
                }
                return full
                        ? new ObjectPage(folders, files, inPlaceToken(lastFile, cursor), true, false)
                        : new ObjectPage(folders, files, inPlaceToken(cursor, null), true, true);
            }
            if (entry.folder() != null) {
                folders.add(toFolder(entry.folder(), folder));
            } else if (!full) {
                files.add(toFile(entry.object(), folder));
                lastFile = entry.object().key();
            } else {
                moreFiles = true;
            }
        }
    }

    private ObjectPage nextPage(String folder, PageToken token, int pageSize) {
        List<Folder> folders = new ArrayList<>();
        List<FileEntry> files = new ArrayList<>();
        while (true) {
            String before = cursor;
            Entry entry = next();
            if (entry == null) {
                return outOfBudget
                        ? new ObjectPage(folders, files, token.resumeAt(cursor).encode(), false, true)
                        : new ObjectPage(folders, files, null, false, false);
            }
            if (entry.folder() != null && token.skipsFolder(entry.folder())) {
                continue;
            }
            if (folders.size() + files.size() == pageSize) {
                return new ObjectPage(folders, files, token.resumeAt(before).encode(), false, false);
            }
            if (entry.folder() != null) {
                folders.add(toFolder(entry.folder(), folder));
            } else {
                files.add(toFile(entry.object(), folder));
            }
        }
    }

    private static String inPlaceToken(String after, String shownUntil) {
        return after == null ? null : PageToken.inPlace(after, shownUntil).encode();
    }

    /** Next folder or file after the cursor; null at the end of the listing or when the budget is spent. */
    private Entry next() {
        while (true) {
            if (index == batch.size() && !(truncated && fetch())) {
                return null;
            }
            S3Object object = batch.get(index++);
            if (skipping != null && object.key().startsWith(skipping)) {
                continue;
            }
            // The empty marker object a console or "New folder" creates is the folder itself, not an entry in it.
            if (object.key().equals(prefix) && prefix.endsWith("/")) {
                continue;
            }
            String folder = folderOf(object.key());
            skipping = folder;
            cursor = folder == null ? object.key() : folder + AFTER_ALL;
            return new Entry(object, folder);
        }
    }

    private boolean fetch() {
        if (requests > 0 && (requests >= MAX_REQUESTS || !clock.instant().isBefore(deadline))) {
            outOfBudget = true;
            return false;
        }
        ListObjectsV2Response response = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(alias)
                .prefix(prefix)
                .startAfter(cursor)
                .maxKeys(skipping != null ? probeSize : BATCH_SIZE)
                .build());
        requests++;
        batch = response.contents();
        index = 0;
        keys += batch.size();
        truncated = Boolean.TRUE.equals(response.isTruncated());
        return !batch.isEmpty();
    }

    /** The next entry needs a sequential read (not a folder jump) and the folder search has read enough keys. */
    private boolean folderSearchSpent() {
        return skipping == null && index == batch.size() && truncated && keys >= FOLDER_SEARCH_KEYS;
    }

    private String folderOf(String key) {
        int slash = key.indexOf('/', prefix.length());
        return slash < 0 ? null : key.substring(0, slash + 1);
    }

    private static Folder toFolder(String folderPrefix, String folder) {
        return new Folder(folderPrefix.substring(folder.length(), folderPrefix.length() - 1), folderPrefix);
    }

    private static FileEntry toFile(S3Object object, String folder) {
        long size = object.size() == null ? 0 : object.size();
        return new FileEntry(object.key().substring(folder.length()), object.key(), size, object.lastModified());
    }
}
