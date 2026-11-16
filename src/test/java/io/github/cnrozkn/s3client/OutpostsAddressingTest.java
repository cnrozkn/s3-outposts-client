package io.github.cnrozkn.s3client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Checks, without a network, what the SDK sends for an S3 on Outposts access point alias: the request goes to the
 * Outpost host and is signed for {@code s3-outposts}, with or without an explicit endpoint. This is why a plain
 * {@code S3Client} works here while generic S3 tools do not.
 */
class OutpostsAddressingTest {

    // Synthetic alias in the documented shape <name>-o<outpost id><suffix>--op-s3; the IDs are all zeros.
    private static final String OUTPOST_ID = "00000000000000000";
    private static final String ALIAS = "test-ap-o" + OUTPOST_ID + "abcdefghijklmnopqrstuvwxy--op-s3";
    private static final String HOST = ALIAS + ".op-" + OUTPOST_ID + ".s3-outposts.eu-west-1.amazonaws.com";
    private static final String ACCOUNT = "000000000000";
    private static final String ARN =
            "arn:aws:s3-outposts:eu-west-1:" + ACCOUNT + ":outpost/op-" + OUTPOST_ID + "/accesspoint/test-ap";
    private static final String ARN_HOST =
            "test-ap-" + ACCOUNT + ".op-" + OUTPOST_ID + ".s3-outposts.eu-west-1.amazonaws.com";
    private static final String SIGNING_SCOPE = "/eu-west-1/s3-outposts/aws4_request";

    private final CapturingHttpClient http = new CapturingHttpClient();

    private S3Client client(String endpoint) {
        S3ClientBuilder builder = S3Client.builder()
                .httpClient(http)
                .region(Region.EU_WEST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("TESTKEYID", "testsecret")));
        if (!endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "https://s3-outposts.eu-west-1.amazonaws.com"})
    void listingGoesToTheOutpostAndIsSignedForS3Outposts(String endpoint) {
        http.respond("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<IsTruncated>false</IsTruncated></ListBucketResult>");
        try (S3Client s3 = client(endpoint)) {
            s3.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(ALIAS).prefix("docs/").build());
        }

        assertThat(http.last().host()).isEqualTo(HOST);
        assertThat(http.last().firstMatchingHeader("Authorization"))
                .hasValueSatisfying(value -> assertThat(value).contains(SIGNING_SCOPE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "https://s3-outposts.eu-west-1.amazonaws.com"})
    void anAccessPointArnWorksLikeTheAlias(String endpoint) {
        http.respond("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<IsTruncated>false</IsTruncated></ListBucketResult>");
        try (S3Client s3 = client(endpoint)) {
            s3.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(ARN).prefix("docs/").build());
        }

        assertThat(http.last().host()).isEqualTo(ARN_HOST);
        assertThat(http.last().firstMatchingHeader("Authorization"))
                .hasValueSatisfying(value -> assertThat(value).contains(SIGNING_SCOPE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "https://s3-outposts.eu-west-1.amazonaws.com"})
    void uploadGoesToTheOutpostAndIsSignedForS3Outposts(String endpoint) {
        http.respond("");
        try (S3Client s3 = client(endpoint)) {
            s3.putObject(
                    PutObjectRequest.builder().bucket(ALIAS).key("docs/a.txt").build(),
                    RequestBody.fromString("hello"));
        }

        assertThat(http.last().host()).isEqualTo(HOST);
        assertThat(http.last().firstMatchingHeader("Authorization"))
                .hasValueSatisfying(value -> assertThat(value).contains(SIGNING_SCOPE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "https://s3-outposts.eu-west-1.amazonaws.com"})
    void copyNamesTheAliasAsItsSource(String endpoint) {
        http.respond("<CopyObjectResult><ETag>\"etag\"</ETag></CopyObjectResult>");
        try (S3Client s3 = client(endpoint)) {
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(ALIAS)
                    .sourceKey("docs/a b.txt")
                    .destinationBucket(ALIAS)
                    .destinationKey("docs/c.txt")
                    .build());
        }

        assertThat(http.last().host()).isEqualTo(HOST);
        assertThat(http.last().firstMatchingHeader("x-amz-copy-source")).hasValue(ALIAS + "/docs/a%20b.txt");
    }

    /** Records each request and answers 200 with a fixed body. */
    private static final class CapturingHttpClient implements SdkHttpClient {

        private final List<SdkHttpRequest> requests = new ArrayList<>();
        private byte[] body = new byte[0];

        void respond(String xml) {
            body = xml.getBytes(StandardCharsets.UTF_8);
        }

        SdkHttpRequest last() {
            return requests.getLast();
        }

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            requests.add(request.httpRequest());
            request.contentStreamProvider().ifPresent(provider -> {
                try (InputStream in = provider.newStream()) {
                    in.transferTo(OutputStream.nullOutputStream());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            byte[] answer = body;
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() {
                    return HttpExecuteResponse.builder()
                            .response(SdkHttpResponse.builder()
                                    .statusCode(200)
                                    .putHeader("Content-Length", String.valueOf(answer.length))
                                    .build())
                            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(answer)))
                            .build();
                }

                @Override
                public void abort() {}
            };
        }

        @Override
        public void close() {}
    }
}
