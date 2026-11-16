package io.github.cnrozkn.s3client.web;

import io.github.cnrozkn.s3client.config.ClientProperties;
import io.github.cnrozkn.s3client.config.ClientProperties.Bucket;
import io.github.cnrozkn.s3client.config.ClientProperties.Capability;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Looks up a configured bucket and checks that the requested operations are turned on for it. */
final class Access {

    private Access() {}

    static Bucket require(ClientProperties properties, String name, Capability... needed) {
        Bucket bucket = properties
                .bucket(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No bucket named " + name));
        for (Capability capability : needed) {
            if (!bucket.allows(capability)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, capability + " is turned off for bucket " + bucket.name());
            }
        }
        return bucket;
    }
}
