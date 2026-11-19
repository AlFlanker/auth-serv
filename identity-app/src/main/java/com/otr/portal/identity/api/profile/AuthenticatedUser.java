package com.otr.portal.identity.api.profile;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Аутентифицированный пользователь публичных профильных эндпоинтов —
 * данные из проверенного Bearer JWT (claims {@code sub}/username,
 * {@code org_code}/{@code org_guid}, {@code auth_source}). Ролей в claims
 * больше нет: проверки ролей берут их из источника (профиль ОШС/ЮСБ или
 * локальная БД) отдельным запросом.
 */
public record AuthenticatedUser(
        String login,
        String orgCode,
        String orgGuid,
        String authSource
) {

    public static AuthenticatedUser from(Jwt jwt) {
        return new AuthenticatedUser(
                jwt.getSubject(),
                jwt.getClaimAsString("org_code"),
                jwt.getClaimAsString("org_guid"),
                jwt.getClaimAsString("auth_source"));
    }
}
