package com.otr.portal.identity.api.profile.view;

/**
 * JSON-контракт монолитного {@code EmployeeInfo} (solutions/fk-portal, пакет ossuserinfo).
 * Порядок полей соответствует порядку объявления в монолите.
 */
public record EmployeeInfoView(
        ProfileFieldView<String> login,
        ProfileFieldView<String> fullName,
        ProfileFieldView<String> organizationName,
        ProfileFieldView<String> departmentName,
        ProfileFieldView<String> titleName,
        ProfileFieldView<String> snils,
        ProfileFieldView<String> phoneWorkplace,
        ProfileFieldView<String> phoneOther,
        ProfileFieldView<String> email,
        ProfileFieldView<String> accountGuid
) {
}
