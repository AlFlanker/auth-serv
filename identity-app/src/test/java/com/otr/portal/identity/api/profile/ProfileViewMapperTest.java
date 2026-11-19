package com.otr.portal.identity.api.profile;

import com.otr.portal.identity.api.profile.view.OssUserInfoView;
import com.otr.portal.identity.api.profile.view.OssUserWorkPhoneView;
import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.model.Profile;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-JSON тесты: wire-формат view совпадает с тем, что сериализуют
 * монолитные {@code OSSUserInfo}, {@code OSSUserWorkPhone},
 * {@code OssReorganizationOrganizationView} (solutions/fk-portal) —
 * UI портала должен получать идентичные JSON.
 */
class ProfileViewMapperTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final ProfileViewMapper MAPPER_UNDER_TEST = new ProfileViewMapper();

    private static Profile profile() {
        return new Profile(
                "ivanov", "Иванов Иван", "Департамент", "Инженер", "+7 900", "+7 901", "123-456",
                "ivanov@example.ru", "ivanov", "ACC-GUID", null, null,
                List.of("ROLE_USER"),
                List.of(new Organization("АО Ромашка", "АО Ромашка", "ORG1", "GUID-1",
                        "7701", "770101001", "1027700000000", "5700", (short) 1)),
                null, null, false);
    }

    @Test
    void toUserInfo_serializesMonolithOssUserInfoContract() throws Exception {
        Profile profile = profile();
        Organization active = profile.organizations().getFirst();
        OssUserInfoView view = MAPPER_UNDER_TEST.toUserInfo(profile, active);

        JsonNode actual = MAPPER.valueToTree(view);
        JsonNode expected = MAPPER.readTree("""
                {
                  "employee": {
                    "login": {"editable": false, "value": "ivanov"},
                    "fullName": {"editable": false, "value": "Иванов Иван"},
                    "organizationName": {"editable": false, "value": "АО Ромашка"},
                    "departmentName": {"editable": false, "value": "Департамент"},
                    "titleName": {"editable": false, "value": "Инженер"},
                    "snils": {"editable": false, "value": "123-456"},
                    "phoneWorkplace": {"editable": true, "value": "+7 900"},
                    "phoneOther": {"editable": false, "value": "+7 901"},
                    "email": {"editable": false, "value": "ivanov@example.ru"},
                    "accountGuid": {"editable": false, "value": "ACC-GUID"}
                  },
                  "organization": {
                    "orgFullName": {"editable": false, "value": "АО Ромашка"},
                    "orgGUID": {"editable": false, "value": "GUID-1"},
                    "orgINN": {"editable": false, "value": "7701"},
                    "orgKPP": {"editable": false, "value": "770101001"},
                    "orgOGRN": {"editable": false, "value": "1027700000000"},
                    "orgTOFK": {"editable": false, "value": "5700"},
                    "orgSvrCode": {"editable": false, "value": "ORG1"}
                  }
                }
                """);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void toWorkPhone_serializesMonolithOssUserWorkPhoneContract() throws Exception {
        OssUserWorkPhoneView view = MAPPER_UNDER_TEST.toWorkPhone(profile());

        JsonNode actual = MAPPER.valueToTree(view);
        JsonNode expected = MAPPER.readTree("""
                {
                  "employee": {
                    "phoneWorkplace": {"editable": true, "value": "+7 900"}
                  }
                }
                """);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void organizationView_serializesMonolithReorganizationViewContract() throws Exception {
        var view = MAPPER_UNDER_TEST.toOrganizationView(
                new Organization("АО Ромашка", "АО Ромашка", "ORG1", "GUID-1",
                        null, null, null, null, (short) 1));

        JsonNode actual = MAPPER.valueToTree(view);
        JsonNode expected = MAPPER.readTree("""
                {"organizationCode": "ORG1", "organizationName": "АО Ромашка", "active": true}
                """);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void organizationView_activeIsFalseForReorganizingOrUnknownStatus() {
        assertThat(MAPPER_UNDER_TEST.toOrganizationView(
                new Organization("АО", "АО", "ORG1", null, null, null, null, null, (short) 4)).active())
                .isFalse();
        assertThat(MAPPER_UNDER_TEST.toOrganizationView(
                new Organization("АО", "АО", "ORG1", null, null, null, null, null, null)).active())
                .isFalse();
    }

    @Test
    void userInfoView_deserializesFromMonolithPostBody() throws Exception {
        OssUserInfoView view = MAPPER.readValue("""
                {"employee": {"login": {"value": "petrov"},
                              "phoneWorkplace": {"value": "+7 999", "editable": true}}}
                """, OssUserInfoView.class);

        assertThat(view.employee()).isNotNull();
        assertThat(view.employee().phoneWorkplace().getValue()).isEqualTo("+7 999");
        assertThat(view.employee().phoneWorkplace().isEditable()).isTrue();
        assertThat(view.organization()).isNull();
    }
}
