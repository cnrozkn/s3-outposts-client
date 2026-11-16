package io.github.cnrozkn.s3client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class S3OutpostsClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(S3OutpostsClientApplication.class, args);
    }
}
