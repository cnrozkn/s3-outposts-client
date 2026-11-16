package io.github.cnrozkn.s3client.web;

import io.github.cnrozkn.s3client.config.ClientProperties;
import io.github.cnrozkn.s3client.config.ClientProperties.Bucket;
import io.github.cnrozkn.s3client.config.ClientProperties.Capability;
import io.github.cnrozkn.s3client.storage.BucketStorage;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Upload, folder creation, rename and delete; each needs its capability on the bucket and a CSRF token. Every write
 * is logged on the {@code s3client.audit} logger.
 */
@Controller
public class WriteController {

    private static final Logger audit = LoggerFactory.getLogger("s3client.audit");
    private static final int MAX_KEY_BYTES = 1024;
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");

    private final BucketStorage storage;
    private final ClientProperties properties;

    public WriteController(BucketStorage storage, ClientProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    /**
     * The browser sends the file as the raw request body and the body is streamed into PutObject, so the server
     * never holds the file in memory or on disk.
     */
    @PostMapping("/buckets/{name}/upload")
    public ResponseEntity<Void> upload(
            @PathVariable String name,
            @RequestParam String key,
            @RequestParam(defaultValue = "false") boolean overwrite,
            HttpServletRequest request,
            Principal principal)
            throws IOException {
        Bucket bucket = Access.require(properties, name, Capability.UPLOAD);
        checkKey(key);
        String contentType = request.getContentType();
        // A form or multipart body would be parsed as request parameters instead of being stored.
        if (contentType != null
                && (contentType.startsWith(MediaType.MULTIPART_FORM_DATA.getType() + "/")
                        || contentType.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE))) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Send the file as the raw body");
        }
        long length = request.getContentLengthLong();
        if (length < 0) {
            throw new ResponseStatusException(HttpStatus.LENGTH_REQUIRED);
        }
        if (length > properties.uploadMaxSize().toBytes()) {
            throw new ResponseStatusException(
                    HttpStatus.CONTENT_TOO_LARGE, "The upload limit is " + properties.uploadMaxSize());
        }
        if (!overwrite && storage.exists(bucket.alias(), key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An object with this key already exists");
        }
        storage.put(
                bucket.alias(),
                key,
                request.getInputStream(),
                length,
                contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType);
        audit.info(
                "action=upload user={} bucket={} key={} size={} overwrite={}",
                principal.getName(),
                bucket.name(),
                logged(key),
                length,
                overwrite);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/buckets/{name}/folder")
    public String createFolder(
            @PathVariable String name,
            @RequestParam(defaultValue = "") String prefix,
            @RequestParam String folder,
            Principal principal,
            RedirectAttributes redirect) {
        Bucket bucket = Access.require(properties, name, Capability.UPLOAD);
        if (folder.isEmpty() || folder.contains("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A folder name cannot be empty or contain '/'");
        }
        String key = prefix + folder + "/";
        checkKey(key);
        storage.createFolder(bucket.alias(), key);
        audit.info("action=create-folder user={} bucket={} key={}", principal.getName(), bucket.name(), logged(key));
        redirect.addFlashAttribute("message", "Created folder " + folder);
        redirect.addAttribute("prefix", prefix);
        return "redirect:/buckets/{name}";
    }

    @PostMapping("/buckets/{name}/rename")
    public String rename(
            @PathVariable String name,
            @RequestParam String key,
            @RequestParam String target,
            @RequestParam(defaultValue = "false") boolean overwrite,
            Principal principal,
            RedirectAttributes redirect) {
        Bucket bucket = Access.require(properties, name, Capability.UPLOAD, Capability.DELETE);
        checkKey(key);
        checkKey(target);
        if (target.endsWith("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The new key cannot end with '/'");
        }
        if (target.equals(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The new key is the same as the old one");
        }
        if (!overwrite && storage.exists(bucket.alias(), target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An object with this key already exists");
        }
        storage.move(bucket.alias(), key, target);
        audit.info(
                "action=rename user={} bucket={} key={} target={} overwrite={}",
                principal.getName(),
                bucket.name(),
                logged(key),
                logged(target),
                overwrite);
        redirect.addFlashAttribute("message", "Renamed " + key + " to " + target);
        redirect.addAttribute("prefix", target.substring(0, target.lastIndexOf('/') + 1));
        return "redirect:/buckets/{name}";
    }

    @PostMapping("/buckets/{name}/delete")
    public String delete(
            @PathVariable String name,
            @RequestParam String key,
            @RequestParam(defaultValue = "") String prefix,
            Principal principal,
            RedirectAttributes redirect) {
        Bucket bucket = Access.require(properties, name, Capability.DELETE);
        checkKey(key);
        storage.delete(bucket.alias(), key);
        audit.info("action=delete user={} bucket={} key={}", principal.getName(), bucket.name(), logged(key));
        redirect.addFlashAttribute("message", "Deleted " + key);
        redirect.addAttribute("prefix", prefix);
        return "redirect:/buckets/{name}";
    }

    /** Keys may contain line breaks; logged as they are, one key could add fake lines to the audit log. */
    private static String logged(String key) {
        return LINE_BREAKS.matcher(key).replaceAll("_");
    }

    private static void checkKey(String key) {
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The key cannot be empty");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keys are limited to 1024 bytes");
        }
    }
}
