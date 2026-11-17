package io.github.cnrozkn.s3client.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cnrozkn.s3client.config.ClientProperties.Capability;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.util.unit.DataSize;

/** WebTest and ServerTest use the YAML form, {@code s3-client.*}; this checks the environment variables. */
class ClientPropertiesTest {

    @Test
    void theEnvironmentVariablesInTheReadmeBind() {
        Map<String, Object> variables = Map.of(
                "S3CLIENT_REGION", "eu-west-1",
                "S3CLIENT_PAGE_SIZE", "7",
                "S3CLIENT_PREVIEW_MAX_SIZE", "1MB",
                "S3CLIENT_UPLOAD_MAX_SIZE", "100MB",
                "S3CLIENT_WRITE_TIMEOUT", "5m",
                "S3CLIENT_LIST_CACHE_TTL", "0",
                "S3CLIENT_BUCKETS_0_NAME", "logs",
                "S3CLIENT_BUCKETS_0_ALIAS", "logs--op-s3",
                "S3CLIENT_BUCKETS_0_CAPABILITIES", "LIST,UPLOAD");
        Binder binder = new Binder(ConfigurationPropertySources.from(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)));
        String prefix = AnnotationUtils.findAnnotation(ClientProperties.class, ConfigurationProperties.class)
                .prefix();

        ClientProperties properties = binder.bindOrCreate(prefix, ClientProperties.class);

        assertThat(properties.region()).isEqualTo("eu-west-1");
        assertThat(properties.pageSize()).isEqualTo(7);
        assertThat(properties.previewMaxSize()).isEqualTo(DataSize.ofMegabytes(1));
        assertThat(properties.uploadMaxSize()).isEqualTo(DataSize.ofMegabytes(100));
        assertThat(properties.writeTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.listCacheTtl()).isZero();
        assertThat(properties.buckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.name()).isEqualTo("logs");
            assertThat(bucket.alias()).isEqualTo("logs--op-s3");
            assertThat(bucket.capabilities()).containsExactlyInAnyOrder(Capability.LIST, Capability.UPLOAD);
        });
    }
}
