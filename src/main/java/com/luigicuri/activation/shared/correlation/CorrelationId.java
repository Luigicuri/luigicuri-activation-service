package com.luigicuri.activation.shared.correlation;

import org.slf4j.MDC;

/**
 * Business correlation identifier: accepted from {@code X-Correlation-Id} (or generated), persisted with the
 * request, echoed in responses and errors, and sent to the provider (R9.2). It lives in the logging MDC as
 * {@code correlationId}. {@code traceId} and {@code spanId} in the MDC belong to the distributed trace
 * (OpenTelemetry), which follows the same request into the worker and the provider call.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    private CorrelationId() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** Id of the distributed trace the current thread is in, or null when nothing is being traced. */
    public static String currentTraceId() {
        return MDC.get(TRACE_ID_MDC_KEY);
    }
}
