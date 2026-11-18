package com.otr.portal.command;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.retry.MaxRetriesExceededException;

import java.util.concurrent.TimeoutException;

/**
 * Классификация исключений Resilience4j в том же порядке, что и лог
 * {@link AbstractResilienceCommand}.
 */
public final class ResilienceFailures {
    private ResilienceFailures() {
    }

    public static ResilienceFailureKind classify(Throwable error) {
        Throwable cause = error.getCause() != null
                ? error.getCause()
                : error;
        if (error instanceof CallNotPermittedException || cause instanceof CallNotPermittedException) {
            return ResilienceFailureKind.CIRCUIT_OPEN;
        }
        if (error instanceof BulkheadFullException || cause instanceof BulkheadFullException) {
            return ResilienceFailureKind.BULKHEAD_FULL;
        }
        if (error instanceof TimeoutException || cause instanceof TimeoutException) {
            return ResilienceFailureKind.TIMEOUT;
        }
        if (error instanceof MaxRetriesExceededException || cause instanceof MaxRetriesExceededException) {
            return ResilienceFailureKind.RETRY_EXHAUSTED;
        }
        return ResilienceFailureKind.OTHER;
    }
}
