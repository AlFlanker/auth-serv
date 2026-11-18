package com.otr.portal.oss.model;

import java.util.List;

/**
 * Профиль пользователя, собранный из ОШС/ЮСБ (аналог {@code OSSUser} монолита, без Spring Security).
 *
 * @param activeOrgCode код СВР активной организации; заполняется автоматически, если организация одна
 * @param activeOrgGuid GUID активной организации; заполняется автоматически, если организация одна
 * @param cacheHit {@code true}, если профиль отдан из кэша {@link com.otr.portal.oss.userdetails.ProfileService},
 *                а не загружен из источника (ОШС/ЮСБ). Вычисляется на каждый запрос в {@code ProfileService.load()}
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
        String activeOrgGuid,
        boolean cacheHit
) {

    public boolean hasSingleOrganization() {
        return organizations != null && organizations.size() == 1;
    }

    /**
     * Копия профиля с признаком источника (кэш/источник), без пересоздания остальных полей.
     */
    public Profile withCacheHit(boolean fromCache) {
        return new Profile(
                login, fullName, departmentName, titleName, phoneWorkplace, phoneOther, snils,
                email, samAccountName, accountGuid, orgFullName, organizationName,
                roles, organizations, activeOrgCode, activeOrgGuid, fromCache);
    }
}
