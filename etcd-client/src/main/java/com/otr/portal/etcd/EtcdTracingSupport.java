package com.otr.portal.etcd;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import lombok.experimental.UtilityClass;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Утилитный класс для трассировки операций ETCD через OpenTelemetry.
 *
 * @author alexraspopin
 * @since 15.05.2026
 */
@UtilityClass
class EtcdTracingSupport {

    static <T> CompletableFuture<T> traceFuture(
            Tracer tracer,
            String spanName,
            Attributes attributes,
            Supplier<CompletableFuture<T>> call
    ) {
        return traceFuture(
                tracer,
                spanName,
                attributes,
                call,
                (span, result) -> {}
        );
    }

    static <T> CompletableFuture<T> traceFuture(
            Tracer tracer,
            String spanName,
            Attributes attributes,
            Supplier<CompletableFuture<T>> call,
            BiConsumer<Span, T> onSuccess
    ) {
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .setAllAttributes(attributes)
                .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            CompletableFuture<T> future = call.get();
            future.whenComplete(traceActionThenCompleted(onSuccess, span));
            return future;
        } catch (Throwable error) {
            traceOnError(span, error);
            span.end();
            throw error;
        }
    }

    private static <T> BiConsumer<T, Throwable> traceActionThenCompleted(BiConsumer<Span, T> onSuccess, Span span) {
        return (result, error) ->
                completeSpan(onSuccess, span, result, error);
    }

    private static <T> void completeSpan(BiConsumer<Span, T> onSuccess, Span span, T result, Throwable error) {
        try {
            if (error != null) {
                traceOnError(span, error);
            } else if (result != null) {
                traceOnSuccess(onSuccess, span, result);
            }
        } finally {
            span.end();
        }
    }

    private static <T> void traceOnSuccess(BiConsumer<Span, T> onSuccess, Span span, T result) {
        try {
            onSuccess.accept(span, result);
        } catch (Throwable instrumentationError) {
            span.recordException(instrumentationError);
            span.setAttribute("otel.instrumentation.error", instrumentationError.getClass().getName());
        }
    }

    private static void traceOnError(Span span, Throwable error) {
        Throwable unwrapped = unwrap(error);
        span.recordException(unwrapped);
        span.setStatus(StatusCode.ERROR, unwrapped.getClass().getName());
        span.setAttribute("error.type", unwrapped.getClass().getName());
    }

    static Throwable unwrap(Throwable error) {
        if ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }
}
