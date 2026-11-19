package com.otr.portal.identity.api.profile.view;

/**
 * JSON-контракт монолитного {@code OSSUserWorkPhone} (solutions/fk-portal, пакет ossuserinfo):
 * {@code {"employee": {"phoneWorkplace": {"editable": true, "value": "..."}}}}.
 */
public record OssUserWorkPhoneView(EmployeeWorkPhoneView employee) {
}
