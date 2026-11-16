package io.github.cnrozkn.s3client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Runs the real server: compression, static caching, and the open probe endpoints on the management port. */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "S3CLIENT_USERNAME=admin",
            "S3CLIENT_PASSWORD=test-password-0123",
            "management.server.port=0",
            "s3-client.region=eu-west-1",
            "s3-client.buckets[0].name=data",
            "s3-client.buckets[0].alias=data--op-s3"
        })
class ServerTest {

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<byte[]> get(int port, String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void staticAssetsAreCompressedAndCached() throws Exception {
        HttpResponse<byte[]> response = get(port, "/css/app.css", "Accept-Encoding", "gzip");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
        assertThat(response.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(value -> assertThat(value).contains("max-age=604800"));
    }

    @Test
    void probesAndMetricsAreOpenOnTheManagementPort() throws Exception {
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode())
                .isEqualTo(200);
        HttpResponse<byte[]> metrics = get(managementPort, "/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(new String(metrics.body())).contains("s3_client_listing_requests");
    }

    @Test
    void theApplicationPortNeedsSignIn() throws Exception {
        HttpResponse<byte[]> response = get(port, "/buckets/data");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location"))
                .hasValueSatisfying(value -> assertThat(value).endsWith("/login"));
    }

    @Test
    void aShortPasswordStopsTheStart() {
        SpringApplication app = new SpringApplicationBuilder(S3OutpostsClientApplication.class)
                .properties(
                        "S3CLIENT_USERNAME=admin",
                        "S3CLIENT_PASSWORD=short",
                        "server.port=0",
                        "management.server.port=0",
                        "s3-client.region=eu-west-1",
                        "s3-client.buckets[0].name=data",
                        "s3-client.buckets[0].alias=data--op-s3")
                .build();

        assertThatThrownBy(app::run).hasRootCauseMessage("S3CLIENT_PASSWORD must have at least 16 characters");
    }
}
