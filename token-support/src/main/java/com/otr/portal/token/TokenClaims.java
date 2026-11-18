package com.otr.portal.token;

import java.util.List;

/**
 * Данные пользователя для claims JWT (единый формат по ADR-02).
 *
 * @param authSource источник аутентификации: имя SSO-заголовка или {@code password}
 */
public record TokenClaims(
        String username,
        List<String> roles,
        String orgGuid,
        String orgCode,
        String authSource
) {
}
