package com.otr.portal.identity.api.profile.view;

import com.otr.portal.oss.model.Organization;

/**
 * JSON-контракт монолитного {@code OssReorganizationOrganizationView}
 * (solutions/fk-portal, пакет ossuserinfo): {@code {"organizationCode", "organizationName", "active"}}.
 * {@code active} — статус реорганизации организации {@code ACTIVE} (1), как в монолите
 * ({@code ReorganizationStatus.ACTIVE}), а не признак текущего выбора.
 */
public record OrganizationView(String organizationCode, String organizationName, boolean active) {

    private static final short ACTIVE_CODE = (short) 1;

    public static OrganizationView from(Organization organization) {
        return new OrganizationView(
                organization.orgSvrCode(),
                organization.organizationName(),
                Short.valueOf(ACTIVE_CODE).equals(organization.reorganizationStatus()));
    }
}
