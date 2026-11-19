package com.otr.portal.token;

/**
 * Данные пользователя для claims JWT (единый формат по ADR-02).
 * Роли в claims не входят: они отдаются отдельным запросом к сервису
 * (внутренний API {@code /api/internal/v1/users/groups} или в теле {@code /profile}).
 *
 * @param authSource источник аутентификации: имя SSO-заголовка или {@code password}
 */
public record TokenClaims(
        String username,
        String orgGuid,
        String orgCode,
        String authSource
) {
}
