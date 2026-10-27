package com.luigicuri.activation.request.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** R6: status transitions are controlled by the domain. Plain unit test, no Spring. */
class ActivationRequestTest {

    private static final Instant T0 = Instant.parse("2026-10-26T14:00:00Z");
    private static final Instant T1 = T0.plusSeconds(1);

    private static ActivationRequest newRequest() {
        return ActivationRequest.create("EXT-1", "CUST-1", "INTERNET-500", "client-a", "trace-1", T0);
    }

    @Test
    void newRequestIsPendingAndImmediatelyEligible() {
        var request = newRequest();

        assertThat(request.getId()).isNotNull();
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getAttempts()).isZero();
        assertThat(request.getNextAttemptAt()).isEqualTo(T0);
        assertThat(request.getCreatedAt()).isEqualTo(T0);
        assertThat(request.getUpdatedAt()).isEqualTo(T0);
    }

    @Nested
    @DisplayName("R6.1 valid transitions")
    class ValidTransitions {

        @Test
        void pendingToProcessingCountsAnAttempt() {
            var request = newRequest();

            request.startProcessing(T1);

            assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
            assertThat(request.getAttempts()).isEqualTo(1);
            assertThat(request.getProcessingStartedAt()).isEqualTo(T1);
            assertThat(request.getUpdatedAt()).isEqualTo(T1);
        }

        @Test
        void processingToCompleted() {
            var request = newRequest();
            request.startProcessing(T1);

            request.complete(T1);

            assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
            assertThat(request.getProcessingStartedAt()).isNull();
        }

        @Test
        void processingToFailedKeepsTheReason() {
            var request = newRequest();
            request.startProcessing(T1);

            request.fail("Provider rejected the request (HTTP 400)", T1);

            assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
            assertThat(request.getFailureReason()).isEqualTo("Provider rejected the request (HTTP 400)");
        }

        @Test
        void failureReasonIsTruncatedToTheColumnSize() {
            var request = newRequest();
            request.startProcessing(T1);

            request.fail("x".repeat(1000), T1);

            assertThat(request.getFailureReason()).hasSize(ActivationRequest.FAILURE_REASON_MAX_LENGTH);
        }

        @Test
        void processingBackToPendingOnRetryKeepsTheAttemptCount() {
            var request = newRequest();
            request.startProcessing(T1);
            var nextAttempt = T1.plus(Duration.ofSeconds(4));

            request.scheduleRetry(nextAttempt, T1);

            assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
            assertThat(request.getNextAttemptAt()).isEqualTo(nextAttempt);
            assertThat(request.getAttempts()).isEqualTo(1);
            assertThat(request.getProcessingStartedAt()).isNull();
        }
    }

    @DisplayName("R6.2 invalid transitions are rejected")
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTransitions")
    void invalidTransitionIsRejected(String description, Consumer<ActivationRequest> arrange,
                                     Consumer<ActivationRequest> transition) {
        var request = newRequest();
        arrange.accept(request);
        var statusBefore = request.getStatus();

        assertThatThrownBy(() -> transition.accept(request))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(request.getStatus()).isEqualTo(statusBefore);
    }

    static Stream<Arguments> invalidTransitions() {
        Consumer<ActivationRequest> pending = r -> { };
        Consumer<ActivationRequest> processing = r -> r.startProcessing(T1);
        Consumer<ActivationRequest> completed = processing.andThen(r -> r.complete(T1));
        Consumer<ActivationRequest> failed = processing.andThen(r -> r.fail("boom", T1));

        Consumer<ActivationRequest> start = r -> r.startProcessing(T1);
        Consumer<ActivationRequest> complete = r -> r.complete(T1);
        Consumer<ActivationRequest> fail = r -> r.fail("boom", T1);
        Consumer<ActivationRequest> retry = r -> r.scheduleRetry(T1, T1);

        return Stream.of(
                Arguments.of("PENDING -> COMPLETED", pending, complete),
                Arguments.of("PENDING -> FAILED", pending, fail),
                Arguments.of("PENDING -> PENDING (retry)", pending, retry),
                Arguments.of("PROCESSING -> PROCESSING", processing, start),
                Arguments.of("COMPLETED -> PROCESSING", completed, start),
                Arguments.of("COMPLETED -> FAILED", completed, fail),
                Arguments.of("COMPLETED -> PENDING (retry)", completed, retry),
                Arguments.of("FAILED -> PROCESSING", failed, start),
                Arguments.of("FAILED -> COMPLETED", failed, complete),
                Arguments.of("FAILED -> PENDING (retry)", failed, retry));
    }

    @Test
    void sameContentMeansSameCustomerAndProduct() {
        var request = newRequest();

        assertThat(request.hasSameContentAs("CUST-1", "INTERNET-500")).isTrue();
        assertThat(request.hasSameContentAs("CUST-1", "TV-100")).isFalse();
        assertThat(request.hasSameContentAs("CUST-2", "INTERNET-500")).isFalse();
    }

    @Test
    void ownershipIsTheCreatingClient() {
        var request = newRequest();

        assertThat(request.isOwnedBy("client-a")).isTrue();
        assertThat(request.isOwnedBy("client-b")).isFalse();
    }
}
