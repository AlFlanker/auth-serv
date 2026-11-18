package com.otr.portal.command;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class ResilienceFailuresTest {

    @Test
    void classifiesResilienceExceptions() {
        assertThat(ResilienceFailures.classify(new TimeoutException("slow")))
                .isEqualTo(ResilienceFailureKind.TIMEOUT);
        assertThat(ResilienceFailures.classify(CallNotPermittedException.createCallNotPermittedException(
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("spi"))))
                .isEqualTo(ResilienceFailureKind.CIRCUIT_OPEN);
        assertThat(ResilienceFailures.classify(BulkheadFullException.createBulkheadFullException(
                io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("spi"))))
                .isEqualTo(ResilienceFailureKind.BULKHEAD_FULL);
        assertThat(ResilienceFailures.classify(new IllegalStateException("other")))
                .isEqualTo(ResilienceFailureKind.OTHER);
    }
}
