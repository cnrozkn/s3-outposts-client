package io.github.cnrozkn.s3client.storage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/** In-memory ListObjectsV2 with Prefix, StartAfter and MaxKeys, ordered the way S3 orders keys. */
final class FakeListing implements S3Client {

    private final NavigableSet<String> keys = new TreeSet<>(KeyScan::compareKeys);
    final List<Integer> maxKeys = new ArrayList<>();
    Runnable onRequest = () -> {};

    void add(String... added) {
        keys.addAll(List.of(added));
    }

    @Override
    public ListObjectsV2Response listObjectsV2(ListObjectsV2Request request) {
        maxKeys.add(request.maxKeys());
        onRequest.run();
        String prefix = request.prefix() == null ? "" : request.prefix();
        String after = request.startAfter();
        NavigableSet<String> from = after != null && KeyScan.compareKeys(after, prefix) >= 0
                ? keys.tailSet(after, false)
                : keys.tailSet(prefix, true);
        List<S3Object> contents = new ArrayList<>();
        boolean truncated = false;
        for (String key : from) {
            if (!key.startsWith(prefix)) {
                break;
            }
            if (contents.size() == request.maxKeys()) {
                truncated = true;
                break;
            }
            contents.add(S3Object.builder()
                    .key(key)
                    .size(1L)
                    .lastModified(Instant.EPOCH)
                    .build());
        }
        return ListObjectsV2Response.builder()
                .contents(contents)
                .isTruncated(truncated)
                .build();
    }

    @Override
    public String serviceName() {
        return SERVICE_NAME;
    }

    @Override
    public void close() {}
}
