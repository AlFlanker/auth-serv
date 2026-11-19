package com.otr.portal.identity.api.profile;

/**
 * Пользователь не аутентифицирован на публичном профильном эндпоинте
 * (нет {@link AuthenticatedUser} в exchange). Маппится в HTTP 401.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException() {
        super("Не авторизован");
    }
}
