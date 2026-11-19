package com.otr.portal.identity.api.profile.view;

/**
 * JSON-контракт монолитного {@code OSSUserInfo} (solutions/fk-portal, пакет ossuserinfo):
 * {@code {"employee": {...}, "organization": {...}}}.
 */
public record OssUserInfoView(EmployeeInfoView employee, OrganizationInfoView organization) {
}
