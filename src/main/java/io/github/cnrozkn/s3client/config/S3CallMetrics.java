package io.github.cnrozkn.s3client.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Times every S3 call as {@code s3.client.calls}, tagged with the operation and its outcome. S3 on Outposts does not
 * offer CloudWatch request metrics, so this is the request-level view of the storage.
 */
final class S3CallMetrics implements ExecutionInterceptor {

    private static final ExecutionAttribute<Long> STARTED = new ExecutionAttribute<>("s3client.started");

    private final MeterRegistry registry;

    S3CallMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
        attributes.putAttribute(STARTED, System.nanoTime());
    }

    @Override
    public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
        record(attributes, "success");
    }

    @Override
    public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
        record(attributes, outcome(context.exception()));
    }

    static String outcome(Throwable failure) {
        if (failure instanceof S3Exception s3) {
            String code =
                    s3.awsErrorDetails() == null ? null : s3.awsErrorDetails().errorCode();
            return code == null ? "http_" + s3.statusCode() : code;
        }
        if (failure instanceof ApiCallTimeoutException || failure instanceof ApiCallAttemptTimeoutException) {
            return "timeout";
        }
        return "client_error";
    }

    private void record(ExecutionAttributes attributes, String outcome) {
        Long started = attributes.getAttribute(STARTED);
        if (started == null) {
            return;
        }
        Timer.builder("s3.client.calls")
                .description("S3 requests made by the application")
                .tag("operation", String.valueOf(attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME)))
                .tag("outcome", outcome)
                .register(registry)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
}
