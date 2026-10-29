package com.luigicuri.activation.shared.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/** With tracing off, nothing is propagated and the work still runs; the traced path is in TracingIT. */
class TraceContextsTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<Propagator> noPropagator = mock(ObjectProvider.class);

    private TraceContexts untraced() {
        given(noPropagator.getIfAvailable(any())).willAnswer(invocation -> invocation.getArgument(0, Supplier.class).get());
        return new TraceContexts(Tracer.NOOP, noPropagator);
    }

    @Test
    void noTraceParentWhenNothingIsTraced() {
        assertThat(untraced().currentTraceParent()).isNull();
    }

    @Test
    void workRunsAndReturnsItsResultWithoutTracing() {
        var result = untraced().inSpan("activation process", null, Map.of("activation.request_id", "id"), () -> 42);

        assertThat(result).isEqualTo(42);
    }
}
