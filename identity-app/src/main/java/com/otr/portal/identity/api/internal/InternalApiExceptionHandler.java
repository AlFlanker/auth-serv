package com.otr.portal.identity.api.internal;

import com.otr.portal.oss.exception.OssUnavailableException;
import com.otr.portal.oss.exception.ProfileNotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeoutException;

/**
 * Единый обработчик ошибок внутреннего API: доменные исключения профиля и сбои Resilience4j.
 */
@Slf4j
@RestControllerAdvice
public class InternalApiExceptionHandler {

    @ExceptionHandler(ProfileNotFoundException.class)
    Mono<ResponseEntity<ErrorView>> notFound(ProfileNotFoundException e) {
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorView("NOT_FOUND", e.getMessage())));
    }

    @ExceptionHandler(OssUnavailableException.class)
    Mono<ResponseEntity<ErrorView>> unavailable(OssUnavailableException e) {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorView("OSS_UNAVAILABLE", e.getMessage())));
    }

    @ExceptionHandler(TimeoutException.class)
    Mono<ResponseEntity<ErrorView>> timeout(TimeoutException e) {
        return Mono.just(ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(new ErrorView("TIMEOUT", "Превышен таймаут обращения к источнику профилей")));
    }

    @ExceptionHandler({CallNotPermittedException.class, BulkheadFullException.class})
    Mono<ResponseEntity<ErrorView>> overloaded(RuntimeException e) {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorView("OVERLOADED", "Источник профилей перегружен, повторите позже")));
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    Mono<ResponseEntity<ErrorView>> unsupported(UnsupportedOperationException e) {
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(new ErrorView("NOT_IMPLEMENTED", e.getMessage())));
    }

    public record ErrorView(String code, String message) {
    }
}
