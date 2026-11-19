package com.otr.portal.identity.api.profile;

/**
 * Организация с указанным кодом СВР не найдена среди организаций пользователя.
 * Маппится в HTTP 404 с кодом {@code ORGANIZATION_NOT_FOUND}
 * (в монолите такое исключение приводило к HTTP 500).
 */
public class OrganizationNotFoundException extends RuntimeException {

    public OrganizationNotFoundException(String username, String organizationCode) {
        super(String.format("Организация с кодом [%s] не найдена у пользователя [%s]", organizationCode, username));
    }
}
