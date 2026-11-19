package com.otr.portal.identity.api.profile;

/**
 * Активная организация не выбрана и не определилась автоматически
 * (мультиорганизационный пользователь). Маппится в HTTP 428 с телом
 * {@code {"errorCode": "ORG_NOT_SELECTED", "error": "..."}} — контракт
 * монолитного {@code OrganizationGuardFilter}, по которому UI открывает
 * модальное окно выбора организации.
 */
public class OrgNotSelectedException extends RuntimeException {

    public static final String ERROR_CODE = "ORG_NOT_SELECTED";

    public OrgNotSelectedException(String message) {
        super(message);
    }
}
