package io.github.cnrozkn.s3client.config;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings under {@code s3-client.*}. Each one can also be set as an environment variable, for example
 * {@code S3CLIENT_REGION} or {@code S3CLIENT_BUCKETS_0_ALIAS}.
 *
 * @param endpoint S3 on Outposts endpoint; when empty the SDK derives it from the access point alias and region
 * @param region Region the Outpost is homed to; when empty the SDK default region chain applies ({@code AWS_REGION})
 * @param pageSize entries per listing page
 * @param previewMaxSize largest object the browser preview opens
 * @param uploadMaxSize largest upload; one PutObject request takes at most 5 GB
 * @param writeTimeout time limit for one upload, copy or delete call
 * @param listCacheTtl how long a listing page is reused; zero turns the cache off
 * @param buckets buckets shown in the UI, each reached through an access point alias or ARN
 */
@ConfigurationProperties("s3-client")
public record ClientProperties(
        URI endpoint,
        String region,
        @DefaultValue("100") int pageSize,
        @DefaultValue("5MB") DataSize previewMaxSize,
        @DefaultValue("5GB") DataSize uploadMaxSize,
        @DefaultValue("30m") Duration writeTimeout,
        @DefaultValue("30s") Duration listCacheTtl,
        List<Bucket> buckets) {

    /** Largest object a single PutObject or CopyObject request accepts. */
    public static final DataSize SINGLE_REQUEST_LIMIT = DataSize.ofGigabytes(5);

    private static final Pattern BUCKET_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    public ClientProperties {
        if (buckets == null || buckets.isEmpty()) {
            throw new IllegalArgumentException(
                    "Configure at least one bucket: S3CLIENT_BUCKETS_0_NAME and S3CLIENT_BUCKETS_0_ALIAS");
        }
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("s3-client.page-size must be between 1 and 1000");
        }
        if (uploadMaxSize.compareTo(SINGLE_REQUEST_LIMIT) > 0) {
            throw new IllegalArgumentException("s3-client.upload-max-size cannot exceed 5GB, the single PUT limit");
        }
        Set<String> names = new HashSet<>();
        for (Bucket bucket : buckets) {
            if (!names.add(bucket.name())) {
                throw new IllegalArgumentException("Duplicate bucket name: " + bucket.name());
            }
        }
        buckets = List.copyOf(buckets);
        region = region == null || region.isBlank() ? null : region;
    }

    public Optional<Bucket> bucket(String name) {
        return buckets.stream().filter(bucket -> bucket.name().equals(name)).findFirst();
    }

    /**
     * A bucket as the UI shows it.
     *
     * @param name short name used in URLs
     * @param alias access point alias ({@code …--op-s3}) or access point ARN
     * @param capabilities allowed operations; when empty, only the read operations
     */
    public record Bucket(String name, String alias, Set<Capability> capabilities) {

        public Bucket {
            if (name == null || !BUCKET_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                        "Bucket names may only use letters, digits, '.', '_' and '-' (S3CLIENT_BUCKETS_<n>_NAME): "
                                + name);
            }
            if (alias == null || alias.isBlank()) {
                throw new IllegalArgumentException(
                        "Bucket " + name + " needs an access point alias or ARN (S3CLIENT_BUCKETS_<n>_ALIAS)");
            }
            capabilities = capabilities == null || capabilities.isEmpty()
                    ? Capability.READ
                    : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
        }

        public boolean allows(Capability capability) {
            return capabilities.contains(capability);
        }

        /** Rename copies the object and deletes the source, so it needs both write capabilities. */
        public boolean canRename() {
            return allows(Capability.UPLOAD) && allows(Capability.DELETE);
        }

        public boolean writable() {
            return allows(Capability.UPLOAD) || allows(Capability.DELETE);
        }
    }

    /** Operations that can be turned on per bucket. Writes are off unless listed. */
    public enum Capability {
        LIST,
        DETAIL,
        DOWNLOAD,
        PREVIEW,
        VERSIONS,
        UPLOAD,
        DELETE;

        public static final Set<Capability> READ =
                Collections.unmodifiableSet(EnumSet.of(LIST, DETAIL, DOWNLOAD, PREVIEW, VERSIONS));
    }
}
