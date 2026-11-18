package com.otr.portal.oss.model;

import ru.otr.hr.ws.organization.v3.TUserInfo;

import static org.springframework.util.StringUtils.hasText;

/**
 * Организация пользователя (аналог {@code OssOrganization} монолита).
 */
public record Organization(
        String organizationFullName,
        String organizationName,
        String orgSvrCode,
        String orgGUID,
        String orgINN,
        String orgKPP,
        String orgOGRN,
        String orgTOFK,
        Short reorganizationStatus
) {

    public static Organization from(TUserInfo tUserInfo) {
        var employee = tUserInfo.getEmployee();
        var organization = tUserInfo.getOrganization();
        var fullName = hasText(organization.getFullName())
                ? organization.getFullName()
                : employee.getFullName();
        return new Organization(
                fullName,
                fullName,
                organization.getSvrCode(),
                organization.getGuid(),
                organization.getInn(),
                organization.getKpp(),
                organization.getOgrn(),
                organization.getTofkCode(),
                organization.getReorganizationStatus());
    }
}
