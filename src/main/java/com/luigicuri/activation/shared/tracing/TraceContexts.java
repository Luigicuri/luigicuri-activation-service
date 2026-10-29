package com.luigicuri.activation.shared.tracing;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * Carries the distributed trace across the asynchronous hop (R9.2). The API stores the W3C
 * {@code traceparent} of the request that created an activation; the worker continues that trace when it
 * calls the provider, so the POST, the processing and the provider call show up as a single trace.
 */
@Component
public class TraceContexts {

    static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    /** With tracing switched off (tests, runs without a collector) spans are no-ops and nothing is propagated. */
    public TraceContexts(Tracer tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
    }

    /** The current trace as a W3C traceparent, or null when nothing is being traced. */
    public String currentTraceParent() {
        var context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /** Runs {@code work} in a new span continuing the stored trace, or in a new trace when there is none. */
    public <T> T inSpan(String name, String traceParent, Map<String, String> tags, Supplier<T> work) {
        var builder = propagator.extract(traceParent, (carrier, key) -> TRACEPARENT.equals(key) ? carrier : null)
                .name(name);
        tags.forEach(builder::tag);
        var span = builder.start();
        try (var scope = tracer.withSpan(span)) {
            return work.get();
        } finally {
            span.end();
        }
    }
}
