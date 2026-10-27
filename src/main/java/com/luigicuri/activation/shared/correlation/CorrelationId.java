package com.luigicuri.activation.shared.correlation;

import org.slf4j.MDC;

/**
 * Correlation identifier that follows a request from the HTTP call through the logs, the error
 * responses and the call to the provider (R9.2). It lives in the logging MDC as {@code traceId}.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "traceId";

    private CorrelationId() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
