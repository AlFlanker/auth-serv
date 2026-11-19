package com.otr.portal.identity.api.profile;

import com.otr.portal.identity.api.profile.view.EmployeeInfoView;
import com.otr.portal.identity.api.profile.view.EmployeeWorkPhoneView;
import com.otr.portal.identity.api.profile.view.OrganizationInfoView;
import com.otr.portal.identity.api.profile.view.OrganizationView;
import com.otr.portal.identity.api.profile.view.OssUserInfoView;
import com.otr.portal.identity.api.profile.view.OssUserWorkPhoneView;
import com.otr.portal.identity.api.profile.view.ProfileFieldView;
import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.model.Profile;
import org.springframework.stereotype.Component;

/**
 * Маппер {@link Profile} в view-формы монолитных контроллеров
 * {@code OSSUserProfileController} (solutions/fk-portal): имена полей и флаги
 * редактируемости совпадают, чтобы UI портала работал без изменений.
 */
@Component
public class ProfileViewMapper {

    /** Имя поля кастомного атрибута — как константа {@code PHONE_WORKPLACE} монолита. */
    public static final String PHONE_WORKPLACE = "phoneWorkplace";

    public OssUserInfoView toUserInfo(Profile profile, Organization activeOrganization) {
        var employee = new EmployeeInfoView(
                ProfileFieldView.of(profile.login()),
                ProfileFieldView.of(profile.fullName()),
                ProfileFieldView.of(activeOrganization.organizationName()),
                ProfileFieldView.of(profile.departmentName()),
                ProfileFieldView.of(profile.titleName()),
                ProfileFieldView.of(profile.snils()),
                ProfileFieldView.editable(profile.phoneWorkplace()),
                ProfileFieldView.of(profile.phoneOther()),
                ProfileFieldView.of(profile.email()),
                ProfileFieldView.of(profile.accountGuid()));
        var organization = new OrganizationInfoView(
                ProfileFieldView.of(activeOrganization.organizationFullName()),
                ProfileFieldView.of(activeOrganization.orgGUID()),
                ProfileFieldView.of(activeOrganization.orgINN()),
                ProfileFieldView.of(activeOrganization.orgKPP()),
                ProfileFieldView.of(activeOrganization.orgOGRN()),
                ProfileFieldView.of(activeOrganization.orgTOFK()),
                ProfileFieldView.of(activeOrganization.orgSvrCode()));
        return new OssUserInfoView(employee, organization);
    }

    public OssUserWorkPhoneView toWorkPhone(Profile profile) {
        return new OssUserWorkPhoneView(
                new EmployeeWorkPhoneView(ProfileFieldView.editable(profile.phoneWorkplace())));
    }

    public OrganizationView toOrganizationView(Organization organization) {
        return OrganizationView.from(organization);
    }
}
