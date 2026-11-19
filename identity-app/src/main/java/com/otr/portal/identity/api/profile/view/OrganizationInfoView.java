package com.otr.portal.identity.api.profile.view;

/**
 * JSON-контракт монолитного {@code OrganizationInfo} (solutions/fk-portal, пакет ossuserinfo).
 */
public record OrganizationInfoView(
        ProfileFieldView<String> orgFullName,
        ProfileFieldView<String> orgGUID,
        ProfileFieldView<String> orgINN,
        ProfileFieldView<String> orgKPP,
        ProfileFieldView<String> orgOGRN,
        ProfileFieldView<String> orgTOFK,
        ProfileFieldView<String> orgSvrCode
) {
}
