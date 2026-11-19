package com.otr.portal.identity.api.profile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

/**
 * Обработчик ошибок публичных профильных эндпоинтов. Сбои источников профилей
 * (404 профиля, 503/504/перегрузка) уже покрывает глобальный
 * {@code InternalApiExceptionHandler} ({@code {code, message}}); здесь — специфика
 * публичного контура: 428-контракт UI монолита и 404 по организации.
 */
@Slf4j
@RestControllerAdvice
public class PublicApiExceptionHandler {

    @ExceptionHandler(UnauthorizedException.class)
    Mono<ResponseEntity<ErrorView>> unauthorized(UnauthorizedException e) {
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorView("UNAUTHORIZED", e.getMessage())));
    }

    @ExceptionHandler(BadRequestException.class)
    Mono<ResponseEntity<ErrorView>> badRequest(BadRequestException e) {
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorView("BAD_REQUEST", e.getMessage())));
    }

    @ExceptionHandler(OrgNotSelectedException.class)
    Mono<ResponseEntity<PreconditionView>> orgNotSelected(OrgNotSelectedException e) {
        // Точный контракт монолитного OrganizationGuardFilter: UI (HttpInterceptor.Service)
        // по errorCode ORG_NOT_SELECTED открывает модальное окно выбора организации.
        return Mono.just(ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                .body(new PreconditionView(OrgNotSelectedException.ERROR_CODE, e.getMessage())));
    }

    @ExceptionHandler(OrganizationNotFoundException.class)
    Mono<ResponseEntity<ErrorView>> organizationNotFound(OrganizationNotFoundException e) {
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorView("ORGANIZATION_NOT_FOUND", e.getMessage())));
    }

    /** Контракт 428 монолита: {@code {"errorCode": "...", "error": "..."}}. */
    public record PreconditionView(String errorCode, String error) {
    }

    public record ErrorView(String code, String message) {
    }
}
