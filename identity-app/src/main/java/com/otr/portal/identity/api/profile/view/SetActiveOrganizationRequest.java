package com.otr.portal.identity.api.profile.view;

/**
 * DTO запроса изменения активной организации — как монолитный
 * {@code SetActiveOrganizationRequest} (solutions/fk-portal, пакет ossuserinfo).
 */
public record SetActiveOrganizationRequest(String organizationCode, String organizationName) {
}
