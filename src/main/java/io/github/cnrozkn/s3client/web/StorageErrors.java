package io.github.cnrozkn.s3client.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Turns S3 failures into status codes the error page explains: 403, 404 and 416 pass through, other S3 errors
 * become 502, and a call that ran out of time becomes 504.
 */
@ControllerAdvice
class StorageErrors {

    private static final Logger log = LoggerFactory.getLogger(StorageErrors.class);

    @ExceptionHandler(NoSuchKeyException.class)
    void missing(HttpServletResponse response) throws IOException {
        send(response, HttpServletResponse.SC_NOT_FOUND, "The object does not exist");
    }

    @ExceptionHandler(S3Exception.class)
    void rejected(S3Exception e, HttpServletResponse response) throws IOException {
        String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
        log.warn("S3 returned {} {}: {}", e.statusCode(), code, e.getMessage());
        int status = switch (e.statusCode()) {
            case 403, 404, 416 -> e.statusCode();
            default -> HttpServletResponse.SC_BAD_GATEWAY;
        };
        send(response, status, "S3 returned " + (code == null ? "HTTP " + e.statusCode() : code));
    }

    @ExceptionHandler(SdkException.class)
    void failed(SdkException e, HttpServletResponse response) throws IOException {
        log.warn("S3 call failed: {}", e.toString());
        boolean timeout = e instanceof ApiCallTimeoutException || e instanceof ApiCallAttemptTimeoutException;
        if (timeout) {
            send(response, HttpServletResponse.SC_GATEWAY_TIMEOUT, "S3 did not answer in time");
        } else {
            send(response, HttpServletResponse.SC_BAD_GATEWAY, "The S3 request could not be completed");
        }
    }

    /** A download that fails half-way has already sent its headers; there is nothing left to tell the browser. */
    private static void send(HttpServletResponse response, int status, String message) throws IOException {
        if (!response.isCommitted()) {
            response.sendError(status, message);
        }
    }
}
