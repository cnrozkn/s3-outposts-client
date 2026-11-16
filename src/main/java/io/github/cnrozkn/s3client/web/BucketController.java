package io.github.cnrozkn.s3client.web;

import io.github.cnrozkn.s3client.config.ClientProperties;
import io.github.cnrozkn.s3client.config.ClientProperties.Bucket;
import io.github.cnrozkn.s3client.config.ClientProperties.Capability;
import io.github.cnrozkn.s3client.model.Folder;
import io.github.cnrozkn.s3client.model.ObjectDetail;
import io.github.cnrozkn.s3client.model.ObjectPage;
import io.github.cnrozkn.s3client.storage.BucketStorage;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Read-only pages: bucket list, object list, object detail, versions, download and preview. */
@Controller
public class BucketController {

    /** One byte range; multi-range requests get the whole object, as S3 does not serve them either. */
    private static final Pattern SINGLE_RANGE = Pattern.compile("bytes=(\\d+-\\d*|-\\d+)");

    private final BucketStorage storage;
    private final ClientProperties properties;

    public BucketController(BucketStorage storage, ClientProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String buckets(Model model) {
        model.addAttribute("buckets", sortedBuckets());
        return "browser";
    }

    /**
     * Each page link carries the tokens of all earlier pages in {@code t}, so Previous works without server state.
     */
    @GetMapping("/buckets/{name}")
    public String objects(
            @PathVariable String name,
            @RequestParam(defaultValue = "") String prefix,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(name = "t", required = false) List<String> t,
            Model model) {
        Bucket bucket = Access.require(properties, name, Capability.LIST);
        List<String> tokens = t == null
                ? List.of()
                : t.stream().filter(token -> !token.isEmpty()).toList();
        String search = q.startsWith(prefix) ? q.substring(prefix.length()) : q;
        ObjectPage page = storage.list(bucket.alias(), prefix, search, tokens.isEmpty() ? null : tokens.getLast());
        model.addAttribute("buckets", sortedBuckets());
        model.addAttribute("bucket", bucket);
        model.addAttribute("prefix", prefix);
        model.addAttribute("q", q);
        model.addAttribute("crumbs", crumbs(prefix));
        model.addAttribute("page", page);
        model.addAttribute("prevTokens", tokens.isEmpty() ? null : tokens.subList(0, tokens.size() - 1));
        model.addAttribute(
                "nextTokens",
                page.nextToken() == null
                        ? null
                        : Stream.concat(tokens.stream(), Stream.of(page.nextToken()))
                                .toList());
        model.addAttribute("uploadMaxSize", properties.uploadMaxSize().toBytes());
        return "browser";
    }

    @GetMapping("/buckets/{name}/object")
    public String object(@PathVariable String name, @RequestParam String key, Model model) {
        Bucket bucket = Access.require(properties, name, Capability.DETAIL);
        ObjectDetail detail = storage.detail(bucket.alias(), key);
        model.addAttribute("bucket", bucket);
        model.addAttribute("detail", detail);
        model.addAttribute("folder", key.substring(0, key.lastIndexOf('/') + 1));
        model.addAttribute(
                "previewable",
                bucket.allows(Capability.PREVIEW)
                        && previewType(detail.contentType(), key) != null
                        && detail.size() <= properties.previewMaxSize().toBytes());
        model.addAttribute("uploadMaxSize", properties.uploadMaxSize().toBytes());
        return "object";
    }

    /** Version rows for one key; when S3 denies listing versions, a single row says so. */
    @GetMapping("/buckets/{name}/versions")
    public String versions(@PathVariable String name, @RequestParam String key, Model model) {
        Bucket bucket = Access.require(properties, name, Capability.VERSIONS);
        model.addAttribute("bucket", bucket);
        model.addAttribute("key", key);
        try {
            model.addAttribute("list", storage.versions(bucket.alias(), key));
        } catch (S3Exception e) {
            if (e.statusCode() != HttpServletResponse.SC_FORBIDDEN) {
                throw e;
            }
            model.addAttribute("denied", true);
        }
        return "versions";
    }

    /** Lets the upload and rename dialogs ask before replacing an object. */
    @GetMapping("/buckets/{name}/exists")
    @ResponseBody
    public Map<String, Boolean> exists(@PathVariable String name, @RequestParam String key) {
        Bucket bucket = Access.require(properties, name, Capability.UPLOAD);
        return Map.of("exists", storage.exists(bucket.alias(), key));
    }

    /**
     * Streams the object. A single {@code Range} is passed to S3, so interrupted downloads can resume. Conditional
     * headers are not forwarded: S3 on Outposts rejects conditional requests.
     */
    @GetMapping("/buckets/{name}/download")
    public void download(
            @PathVariable String name,
            @RequestParam String key,
            @RequestParam(required = false) String version,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            HttpServletResponse response)
            throws IOException {
        Bucket bucket = Access.require(properties, name, Capability.DOWNLOAD);
        if (version != null) {
            Access.require(properties, name, Capability.VERSIONS);
        }
        String s3Range = range != null && SINGLE_RANGE.matcher(range.trim()).matches() ? range.trim() : null;
        try (ResponseInputStream<GetObjectResponse> object = storage.open(bucket.alias(), key, version, s3Range)) {
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            String contentRange = object.response().contentRange();
            if (s3Range != null && contentRange != null) {
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                response.setHeader(HttpHeaders.CONTENT_RANGE, contentRange);
            }
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            stream(object, ContentDisposition.attachment(), key, response);
        }
    }

    /** Opens images, PDFs and text in the browser, sandboxed so a stored page cannot run script. */
    @GetMapping("/buckets/{name}/preview")
    public void preview(@PathVariable String name, @RequestParam String key, HttpServletResponse response)
            throws IOException {
        Bucket bucket = Access.require(properties, name, Capability.PREVIEW);
        try (ResponseInputStream<GetObjectResponse> object = storage.open(bucket.alias(), key, null, null)) {
            MediaType type = previewType(object.response().contentType(), key);
            Long length = object.response().contentLength();
            if (type == null
                    || length != null && length > properties.previewMaxSize().toBytes()) {
                object.abort();
                throw new ResponseStatusException(
                        type == null ? HttpStatus.UNSUPPORTED_MEDIA_TYPE : HttpStatus.CONTENT_TOO_LARGE);
            }
            response.setContentType(type.toString());
            response.setHeader("Content-Security-Policy", "sandbox");
            stream(object, ContentDisposition.inline(), key, response);
        }
    }

    private List<Bucket> sortedBuckets() {
        return properties.buckets().stream()
                .sorted(Comparator.comparing(Bucket::name))
                .toList();
    }

    private static List<Folder> crumbs(String prefix) {
        List<Folder> crumbs = new ArrayList<>();
        int start = 0;
        for (int end = prefix.indexOf('/'); end >= 0; end = prefix.indexOf('/', start)) {
            crumbs.add(new Folder(prefix.substring(start, end), prefix.substring(0, end + 1)));
            start = end + 1;
        }
        return crumbs;
    }

    /** Images except SVG (it can carry script), PDF, and text-like types shown as plain text. */
    static MediaType previewType(String contentType, String key) {
        MediaType type = parse(contentType);
        if (type == null || MediaType.APPLICATION_OCTET_STREAM.equalsTypeAndSubtype(type)) {
            type = MediaTypeFactory.getMediaType(key).orElse(null);
        }
        if (type == null) {
            return null;
        }
        String subtype = type.getSubtype();
        if ("image".equals(type.getType()) && !subtype.contains("svg")
                || MediaType.APPLICATION_PDF.equalsTypeAndSubtype(type)) {
            return new MediaType(type.getType(), subtype);
        }
        if ("text".equals(type.getType())
                || subtype.endsWith("xml")
                || subtype.endsWith("json")
                || "message".equals(type.getType()) && "rfc822".equals(subtype)) {
            return new MediaType(
                    MediaType.TEXT_PLAIN, type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset());
        }
        return null;
    }

    private static MediaType parse(String contentType) {
        try {
            return contentType == null ? null : MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void stream(
            ResponseInputStream<GetObjectResponse> object,
            ContentDisposition.Builder disposition,
            String key,
            HttpServletResponse response)
            throws IOException {
        Long length = object.response().contentLength();
        if (length != null) {
            response.setContentLengthLong(length);
        }
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                disposition
                        .filename(fileName.isEmpty() ? "object" : fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString());
        try {
            object.transferTo(response.getOutputStream());
        } catch (IOException e) {
            object.abort();
            throw e;
        }
    }
}
