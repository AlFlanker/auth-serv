package com.otr.portal.identity.api.profile;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Аутентифицированный пользователь публичных профильных эндпоинтов —
 * данные из проверенного Bearer JWT (claims {@code sub}/username, roles,
 * {@code org_code}/{@code org_guid}, {@code auth_source}).
 */
public record AuthenticatedUser(
        String login,
        List<String> roles,
        String orgCode,
        String orgGuid,
        String authSource
) {

    public static AuthenticatedUser from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new AuthenticatedUser(
                jwt.getSubject(),
                roles == null ? List.of() : roles,
                jwt.getClaimAsString("org_code"),
                jwt.getClaimAsString("org_guid"),
                jwt.getClaimAsString("auth_source"));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
