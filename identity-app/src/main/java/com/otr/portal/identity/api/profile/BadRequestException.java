package com.otr.portal.identity.api.profile;

/**
 * Некорректное тело запроса публичного профильного эндпоинта.
 * Маппится в HTTP 400 с кодом {@code BAD_REQUEST}.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
