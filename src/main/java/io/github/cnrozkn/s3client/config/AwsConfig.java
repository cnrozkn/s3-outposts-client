package io.github.cnrozkn.s3client.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Builds the S3 client. Credentials come from the SDK default chain: environment variables, profile, container or
 * instance role.
 */
@Configuration(proxyBeanMethods = false)
class AwsConfig {

    /**
     * A read call, retries included, ends before the 60 second idle timeout common on proxies and load balancers,
     * so a slow S3 answer reaches the user as an error page instead of a cut connection. Body streaming of a
     * download is not counted.
     */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(10);

    private static final int MAX_CONNECTIONS = 100;

    /** Apache HttpClient 5 based; unlike the 4.x client it is safe on virtual threads. */
    @Bean(destroyMethod = "close")
    SdkHttpClient s3HttpClient() {
        return Apache5HttpClient.builder().maxConnections(MAX_CONNECTIONS).build();
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client(ClientProperties properties, SdkHttpClient s3HttpClient, MeterRegistry registry) {
        S3ClientBuilder builder = S3Client.builder()
                .httpClient(s3HttpClient)
                .overrideConfiguration(config -> config.apiCallTimeout(CALL_TIMEOUT)
                        .apiCallAttemptTimeout(ATTEMPT_TIMEOUT)
                        .addExecutionInterceptor(new S3CallMetrics(registry)));
        if (properties.endpoint() != null) {
            builder.endpointOverride(properties.endpoint());
        }
        if (properties.region() != null) {
            builder.region(Region.of(properties.region()));
        }
        return builder.build();
    }
}
