package io.github.cnrozkn.s3client.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cnrozkn.s3client.model.FileEntry;
import io.github.cnrozkn.s3client.model.Folder;
import io.github.cnrozkn.s3client.model.ObjectPage;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyScanTest {

    private final FakeListing s3 = new FakeListing();
    private final InstantSource stoppedClock = InstantSource.fixed(Instant.EPOCH);

    private KeyScan.Listed page(String folder, String search, String token, int pageSize) {
        return KeyScan.list(s3, "alias", folder, search, token, pageSize, KeyScan.PROBE_SIZE, stoppedClock);
    }

    /** Walks every page of a folder the way the Next button does. */
    private Walk walk(String folder, int pageSize, int probeSize) {
        Walk walk = new Walk();
        String token = null;
        do {
            KeyScan.Listed listed = KeyScan.list(s3, "alias", folder, "", token, pageSize, probeSize, stoppedClock);
            ObjectPage page = listed.page();
            if (walk.pages == 0) {
                walk.firstPage = page;
            }
            walk.pages++;
            walk.maxRequests = Math.max(walk.maxRequests, listed.requests());
            walk.keys += listed.keys();
            walk.paused += page.paused() ? 1 : 0;
            page.folders().forEach(entry -> walk.folders.add(entry.prefix()));
            page.files().forEach(entry -> walk.files.add(entry.key()));
            token = page.nextToken();
        } while (token != null && walk.pages < 1000);
        return walk;
    }

    private static final class Walk {
        ObjectPage firstPage;
        int pages;
        int maxRequests;
        int keys;
        int paused;
        final List<String> folders = new ArrayList<>();
        final List<String> files = new ArrayList<>();
    }

    private void addLargeFolders(int folders, int keysEach) {
        for (int folder = 0; folder < folders; folder++) {
            for (int key = 0; key < keysEach; key++) {
                s3.add("d%02d/k%04d".formatted(folder, key));
            }
        }
    }

    @Test
    void foldersComeFirstWithNamesRelativeToTheFolder() {
        s3.add("docs/a.txt", "docs/b/1", "docs/b/2", "docs/c.txt", "docs/d/1");

        ObjectPage page = page("docs/", "", null, 10).page();

        assertThat(page.folders()).extracting(Folder::name).containsExactly("b", "d");
        assertThat(page.files()).extracting(FileEntry::name).containsExactly("a.txt", "c.txt");
        assertThat(page.nextToken()).isNull();
    }

    @Test
    void pagesThroughFilesInOrder() {
        for (int i = 0; i < 250; i++) {
            s3.add("f%03d".formatted(i));
        }

        Walk walk = walk("", 100, KeyScan.PROBE_SIZE);

        assertThat(walk.pages).isEqualTo(3);
        assertThat(walk.files).hasSize(250).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void foldersAfterTheFirstPageAreShownOnTopOnce() {
        for (int i = 0; i < 150; i++) {
            s3.add("f%03d".formatted(i));
        }
        s3.add("z/1", "z/2");

        ObjectPage first = page("", "", null, 100).page();
        ObjectPage second = page("", "", first.nextToken(), 100).page();

        assertThat(first.folders()).extracting(Folder::name).containsExactly("z");
        assertThat(first.files()).hasSize(100);
        assertThat(second.folders()).isEmpty();
        assertThat(second.files()).hasSize(50);
        assertThat(second.nextToken()).isNull();
    }

    @Test
    void whenTheFolderSearchStopsEarlyLaterPagesDoNotRepeatFolders() {
        for (int i = 0; i < 3000; i++) {
            s3.add("f%04d".formatted(i));
        }
        s3.add("f0150-dir/x", "f2500-dir/x");

        Walk walk = walk("", 100, KeyScan.PROBE_SIZE);

        assertThat(walk.firstPage.foldersPartial()).isTrue();
        assertThat(walk.firstPage.folders()).extracting(Folder::name).containsExactly("f0150-dir");
        assertThat(walk.folders).containsExactly("f0150-dir/", "f2500-dir/");
        assertThat(walk.files).hasSize(3000).doesNotHaveDuplicates();
    }

    @Test
    void everyPageStaysWithinTheRequestBudget() {
        addLargeFolders(30, 1500);

        Walk walk = walk("", 100, KeyScan.PROBE_SIZE);

        assertThat(walk.maxRequests).isLessThanOrEqualTo(KeyScan.MAX_REQUESTS);
        assertThat(walk.paused).isPositive();
        assertThat(walk.folders).hasSize(30).doesNotHaveDuplicates();
    }

    @Test
    void aPageStopsWhenItsTimeIsUp() {
        addLargeFolders(30, 1500);
        Instant[] now = {Instant.EPOCH};
        s3.onRequest = () -> now[0] = now[0].plus(Duration.ofSeconds(1));

        KeyScan.Listed listed = KeyScan.list(s3, "alias", "", "", null, 100, KeyScan.PROBE_SIZE, () -> now[0]);

        assertThat(listed.requests()).isEqualTo(3);
        assertThat(listed.page().paused()).isTrue();
        assertThat(listed.page().nextToken()).isNotNull();
    }

    @Test
    void smallBatchesAfterFolderJumpsGiveTheSameResultWithFewerKeys() {
        addLargeFolders(30, 1500);

        Walk probing = walk("", 100, KeyScan.PROBE_SIZE);
        Walk fixed = walk("", 100, KeyScan.BATCH_SIZE);

        assertThat(probing.folders).isEqualTo(fixed.folders);
        assertThat(probing.files).isEqualTo(fixed.files);
        assertThat(probing.keys).isLessThan(fixed.keys / 3);
        assertThat(s3.maxKeys).contains(KeyScan.PROBE_SIZE);
    }

    @Test
    void searchNarrowsByPrefixAndKeepsNamesRelativeToTheFolder() {
        s3.add("logs/2024-01/a", "logs/2024-02/b", "logs/2025-01/c", "logs/2024.txt");

        ObjectPage page = page("logs/", "2024", null, 10).page();

        assertThat(page.folders()).extracting(Folder::name).containsExactly("2024-01", "2024-02");
        assertThat(page.files()).extracting(FileEntry::name).containsExactly("2024.txt");
    }

    @Test
    void keysCompareByCodePointLikeS3() {
        String supplementary = new String(Character.toChars(0x1F600));
        assertThat(KeyScan.compareKeys("�", supplementary)).isNegative();
        assertThat(KeyScan.compareKeys("a/", "a/" + KeyScan.AFTER_ALL)).isNegative();
        assertThat(KeyScan.compareKeys("a/" + supplementary, "a/" + KeyScan.AFTER_ALL))
                .isNegative();
    }

    @Test
    void tokensRoundTripAndBadTokensMeanTheFirstPage() {
        PageToken token = PageToken.inPlace("a:b", "c");
        assertThat(PageToken.parse(token.encode())).isEqualTo(token);
        assertThat(PageToken.parse(PageToken.filesOnly("x").encode())).isEqualTo(PageToken.filesOnly("x"));
        assertThat(PageToken.parse("A99:abc")).isNull();
        assertThat(PageToken.parse("Axx:abc")).isNull();
        assertThat(PageToken.parse("Qabc")).isNull();
        assertThat(PageToken.parse("A")).isNull();
    }
}
