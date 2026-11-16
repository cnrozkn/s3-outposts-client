package io.github.cnrozkn.s3client.storage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.cnrozkn.s3client.config.ClientProperties;
import io.github.cnrozkn.s3client.model.ObjectDetail;
import io.github.cnrozkn.s3client.model.ObjectPage;
import io.github.cnrozkn.s3client.model.ObjectVersion;
import io.github.cnrozkn.s3client.model.VersionList;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.io.InputStream;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Reads and writes bucket contents through access point aliases. */
@Service
public class BucketStorage {

    private static final int VERSION_LIMIT = 100;
    private static final int LIST_CACHE_SIZE = 1000;

    private final S3Client s3;
    private final ClientProperties properties;
    private final Cache<ListKey, ObjectPage> listCache;
    private final DistributionSummary listingRequests;
    private final DistributionSummary listingKeys;

    private record ListKey(String alias, String folder, String search, String token) {}

    public BucketStorage(S3Client s3, ClientProperties properties, MeterRegistry registry) {
        this.s3 = s3;
        this.properties = properties;
        Duration ttl = properties.listCacheTtl();
        if (ttl.isZero() || ttl.isNegative()) {
            listCache = null;
        } else {
            listCache = Caffeine.newBuilder()
                    .expireAfterWrite(ttl)
                    .maximumSize(LIST_CACHE_SIZE)
                    .recordStats()
                    .build();
            CaffeineCacheMetrics.monitor(registry, listCache, "s3client.list");
        }
        listingRequests = DistributionSummary.builder("s3.client.listing.requests")
                .description("S3 requests needed for one listing page")
                .register(registry);
        listingKeys = DistributionSummary.builder("s3.client.listing.keys")
                .description("Keys read from S3 for one listing page")
                .register(registry);
    }

    /**
     * A listing page. Pages are kept for {@code list-cache-ttl}, so paging back and forth does not hit S3 again;
     * every write to the bucket drops its pages.
     */
    public ObjectPage list(String alias, String folder, String search, String token) {
        ListKey key = new ListKey(alias, folder, search, token);
        ObjectPage cached = listCache == null ? null : listCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        KeyScan.Listed listed = KeyScan.list(
                s3, alias, folder, search, token, properties.pageSize(), KeyScan.PROBE_SIZE, InstantSource.system());
        listingRequests.record(listed.requests());
        listingKeys.record(listed.keys());
        if (listCache != null) {
            listCache.put(key, listed.page());
        }
        return listed.page();
    }

    public ObjectDetail detail(String alias, String key) {
        HeadObjectResponse head = head(alias, key);
        return new ObjectDetail(
                key, size(head.contentLength()), head.contentType(), head.eTag(), head.lastModified(), head.metadata());
    }

    /**
     * Versions of one key. The key itself is the prefix; versions of longer keys sharing it are dropped. S3 sorts
     * by key, so the versions of the key itself come first.
     */
    public VersionList versions(String alias, String key) {
        ListObjectVersionsResponse response = s3.listObjectVersions(ListObjectVersionsRequest.builder()
                .bucket(alias)
                .prefix(key)
                .maxKeys(VERSION_LIMIT)
                .build());
        Stream<ObjectVersion> versions = response.versions().stream()
                .filter(version -> key.equals(version.key()))
                .map(version -> new ObjectVersion(
                        version.versionId(),
                        Boolean.TRUE.equals(version.isLatest()),
                        false,
                        size(version.size()),
                        version.lastModified()));
        Stream<ObjectVersion> markers = response.deleteMarkers().stream()
                .filter(marker -> key.equals(marker.key()))
                .map(marker -> new ObjectVersion(
                        marker.versionId(), Boolean.TRUE.equals(marker.isLatest()), true, 0, marker.lastModified()));
        List<ObjectVersion> all = Stream.concat(versions, markers)
                .sorted(Comparator.comparing(ObjectVersion::lastModified).reversed())
                .toList();
        boolean truncated = Boolean.TRUE.equals(response.isTruncated()) && key.equals(response.nextKeyMarker());
        return new VersionList(all, truncated);
    }

    /**
     * Opens the object as a stream.
     *
     * @param versionId null for the current version
     * @param range a single {@code bytes=} range, or null for the whole object
     */
    public ResponseInputStream<GetObjectResponse> open(String alias, String key, String versionId, String range) {
        return s3.getObject(GetObjectRequest.builder()
                .bucket(alias)
                .key(key)
                .versionId(versionId)
                .range(range)
                .build());
    }

    /** S3 on Outposts rejects conditional writes, so overwrite checks are a HeadObject before the write. */
    public boolean exists(String alias, String key) {
        try {
            head(alias, key);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /** Streams {@code length} bytes from {@code body} into the object; nothing is buffered on disk. */
    public void put(String alias, String key, InputStream body, long length, String contentType) {
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(alias)
                            .key(key)
                            .contentType(contentType)
                            .contentLength(length)
                            .overrideConfiguration(writeTimeout())
                            .build(),
                    RequestBody.fromInputStream(body, length));
        } finally {
            evict(alias);
        }
    }

    /** Folders do not exist in S3; an empty object named {@code prefix/} makes an empty folder show up. */
    public void createFolder(String alias, String folderKey) {
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(alias)
                            .key(folderKey)
                            .overrideConfiguration(writeTimeout())
                            .build(),
                    RequestBody.empty());
        } finally {
            evict(alias);
        }
    }

    /** With versioning on, S3 keeps the older versions and adds a delete marker. */
    public void delete(String alias, String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(alias)
                    .key(key)
                    .overrideConfiguration(writeTimeout())
                    .build());
        } finally {
            evict(alias);
        }
    }

    /**
     * Server-side copy, then delete of the source. Not atomic: if the copy fails the source stays; if the delete
     * fails both keys exist. Content type, metadata and tags are copied along.
     */
    public void move(String alias, String from, String to) {
        if (size(head(alias, from).contentLength()) > ClientProperties.SINGLE_REQUEST_LIMIT.toBytes()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Objects over 5 GB cannot be renamed: CopyObject is limited to 5 GB");
        }
        try {
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(alias)
                    .sourceKey(from)
                    .destinationBucket(alias)
                    .destinationKey(to)
                    .overrideConfiguration(writeTimeout())
                    .build());
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(alias)
                    .key(from)
                    .overrideConfiguration(writeTimeout())
                    .build());
        } finally {
            evict(alias);
        }
    }

    private HeadObjectResponse head(String alias, String key) {
        return s3.headObject(HeadObjectRequest.builder().bucket(alias).key(key).build());
    }

    private AwsRequestOverrideConfiguration writeTimeout() {
        Duration timeout = properties.writeTimeout();
        return AwsRequestOverrideConfiguration.builder()
                .apiCallTimeout(timeout)
                .apiCallAttemptTimeout(timeout)
                .build();
    }

    private void evict(String alias) {
        if (listCache != null) {
            listCache.asMap().keySet().removeIf(key -> key.alias().equals(alias));
        }
    }

    private static long size(Long value) {
        return value == null ? 0 : value;
    }
}
