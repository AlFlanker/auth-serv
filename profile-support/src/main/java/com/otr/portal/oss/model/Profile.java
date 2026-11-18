package com.otr.portal.oss.model;

import java.util.List;

/**
 * Профиль пользователя, собранный из ОШС/ЮСБ (аналог {@code OSSUser} монолита, без Spring Security).
 *
 * @param activeOrgCode код СВР активной организации; заполняется автоматически, если организация одна
 * @param activeOrgGuid GUID активной организации; заполняется автоматически, если организация одна
 */
public record Profile(
        String login,
        String fullName,
        String departmentName,
        String titleName,
        String phoneWorkplace,
        String phoneOther,
        String snils,
        String email,
        String samAccountName,
        String accountGuid,
        String orgFullName,
        String organizationName,
        List<String> roles,
        List<Organization> organizations,
        String activeOrgCode,
        String activeOrgGuid
) {

    public boolean hasSingleOrganization() {
        return organizations != null && organizations.size() == 1;
    }
}
