package io.github.cnrozkn.s3client.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.cnrozkn.s3client.config.ClientProperties;
import io.github.cnrozkn.s3client.config.ClientProperties.Bucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

class BucketStorageTest {

    private static final String ALIAS = "data--op-s3";

    private final S3Client s3 = mock(S3Client.class);
    private final BucketStorage storage = storage(Duration.ofSeconds(30));

    private BucketStorage storage(Duration listCacheTtl) {
        ClientProperties properties = new ClientProperties(
                null,
                "eu-west-1",
                100,
                DataSize.ofMegabytes(5),
                DataSize.ofGigabytes(5),
                Duration.ofMinutes(30),
                listCacheTtl,
                List.of(new Bucket("data", ALIAS, null)));
        return new BucketStorage(s3, properties, new SimpleMeterRegistry());
    }

    @BeforeEach
    void oneObject() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(S3Object.builder()
                                .key("a.txt")
                                .size(1L)
                                .lastModified(Instant.EPOCH)
                                .build())
                        .isTruncated(false)
                        .build());
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(10L).build());
    }

    @Test
    void repeatedListingComesFromTheCache() {
        storage.list(ALIAS, "", "", null);
        storage.list(ALIAS, "", "", null);

        verify(s3, times(1)).listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    void aWriteDropsTheCachedPagesOfThatBucket() {
        storage.list(ALIAS, "", "", null);
        storage.delete(ALIAS, "a.txt");
        storage.list(ALIAS, "", "", null);

        verify(s3, times(2)).listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    void theCacheCanBeTurnedOff() {
        BucketStorage uncached = storage(Duration.ZERO);

        uncached.list(ALIAS, "", "", null);
        uncached.list(ALIAS, "", "", null);

        verify(s3, times(2)).listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    void renameCopiesOnTheServerThenDeletesTheSource() {
        storage.move(ALIAS, "a.txt", "b/a.txt");

        ArgumentCaptor<CopyObjectRequest> copy = ArgumentCaptor.forClass(CopyObjectRequest.class);
        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        InOrder order = inOrder(s3);
        order.verify(s3).copyObject(copy.capture());
        order.verify(s3).deleteObject(delete.capture());
        assertThat(copy.getValue().sourceBucket()).isEqualTo(ALIAS);
        assertThat(copy.getValue().sourceKey()).isEqualTo("a.txt");
        assertThat(copy.getValue().destinationBucket()).isEqualTo(ALIAS);
        assertThat(copy.getValue().destinationKey()).isEqualTo("b/a.txt");
        assertThat(delete.getValue().key()).isEqualTo("a.txt");
    }

    @Test
    void aFailedCopyKeepsTheSource() {
        when(s3.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).build());

        assertThatThrownBy(() -> storage.move(ALIAS, "a.txt", "b.txt")).isInstanceOf(S3Exception.class);

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void objectsOverFiveGigabytesAreNotRenamed() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(DataSize.ofGigabytes(6).toBytes())
                        .build());

        assertThatThrownBy(() -> storage.move(ALIAS, "big.bin", "other.bin"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(s3, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void uploadStreamsTheBodyWithItsLengthAndTheWriteTimeout() {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);

        storage.put(ALIAS, "a.txt", new ByteArrayInputStream(body), body.length, "text/plain");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> requestBody = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), requestBody.capture());
        assertThat(request.getValue().contentLength()).isEqualTo(5L);
        assertThat(request.getValue().contentType()).isEqualTo("text/plain");
        assertThat(request.getValue().overrideConfiguration())
                .hasValueSatisfying(
                        config -> assertThat(config.apiCallTimeout()).contains(Duration.ofMinutes(30)));
        assertThat(requestBody.getValue().optionalContentLength()).contains(5L);
    }
}
