package com.otr.portal.oss.userdetails;

import com.otr.portal.command.Command;
import com.otr.portal.config.source.ConfigKey;
import com.otr.portal.config.source.ConfigSource;
import com.otr.portal.oss.config.OssConfig;
import com.otr.portal.oss.config.UsbConfig;
import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.userdetails.command.OssCommandFactory;
import com.otr.portal.oss.userdetails.command.model.GetAuthoritiesRequest;
import com.otr.portal.oss.userdetails.command.model.GetUserDetailsRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;
import ru.otr.hr.ws.organization.v3.GetUserGroupsResponse;
import ru.otr.hr.ws.organization.v3.GetUserInfoResponse;
import ru.otr.hr.ws.organization.v3.TEmployee;
import ru.otr.hr.ws.organization.v3.TOrganization;
import ru.otr.hr.ws.organization.v3.TUserInfo;
import ru.otr.hr.ws.organization.v3.TUserInfos;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты {@link ProfileService}: сборка профиля, автовыбор единственной организации,
 * роли по умолчанию при отключённых группах.
 */
class ProfileServiceTest {

    private OssCommandFactory commandFactory;
    private ConfigSource configSource;
    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        commandFactory = Mockito.mock(OssCommandFactory.class);
        configSource = Mockito.mock(ConfigSource.class);

        var ossConfigKey = ConfigKey.of("OSSConfig", OssConfig.class, raw -> null);
        var usbConfigKey = ConfigKey.of("UsbConfig", UsbConfig.class, raw -> null);

        when(configSource.get(ossConfigKey)).thenReturn(Mono.just(new OssConfig(
                OssConfig.OssType.SOBI_WEB_SERVICE, "http://oss/OrganizationService.wsdl", "secure-key",
                60, "X-Auth", true, 1, 20, 1, 20, 5, 20, "User-Selected-OrgCode")));
        when(configSource.get(usbConfigKey)).thenReturn(Mono.just(new UsbConfig(false, null, null, null)));

        profileService = new ProfileService(
                commandFactory,
                configSource,
                ossConfigKey,
                usbConfigKey,
                Mockito.mock(ApplicationEventPublisher.class),
                new SimpleMeterRegistry());
    }

    @Test
    void load_assemblesProfileWithRolesAndSingleOrganizationAutoSelected() {
        var userInfoResponse = userInfoResponse(List.of(
                userInfo("Иванов Иван", "ORG-CODE-1", "GUID-1")));
        when(commandFactory.buildCommand(any(GetUserDetailsRequest.class)))
                .thenReturn(Mono.just((Command<GetUserInfoResponse>) () -> Mono.just(userInfoResponse)));
        when(commandFactory.buildCommand(any(GetAuthoritiesRequest.class)))
                .thenReturn(Mono.just((Command<List<String>>) () -> Mono.just(List.of("ROLE_ADMIN", "ROLE_USER"))));

        var profile = profileService.load("ivanov").block();

        assertThat(profile).isNotNull();
        assertThat(profile.login()).isEqualTo("ivanov");
        assertThat(profile.fullName()).isEqualTo("Иванов Иван");
        assertThat(profile.roles()).containsExactly("ROLE_ADMIN", "ROLE_USER");
        assertThat(profile.organizations()).hasSize(1);
        assertThat(profile.activeOrgCode()).isEqualTo("ORG-CODE-1");
        assertThat(profile.activeOrgGuid()).isEqualTo("GUID-1");
        assertThat(profile.organizationName()).isEqualTo("Организация");
    }

    @Test
    void load_withMultipleOrganizations_doesNotAutoSelect() {
        var userInfoResponse = userInfoResponse(List.of(
                userInfo("Иванов Иван", "ORG-CODE-1", "GUID-1"),
                userInfo("Иванов Иван", "ORG-CODE-2", "GUID-2")));
        when(commandFactory.buildCommand(any(GetUserDetailsRequest.class)))
                .thenReturn(Mono.just((Command<GetUserInfoResponse>) () -> Mono.just(userInfoResponse)));
        when(commandFactory.buildCommand(any(GetAuthoritiesRequest.class)))
                .thenReturn(Mono.just((Command<List<String>>) () -> Mono.just(List.of())));

        var profile = profileService.load("ivanov").block();

        assertThat(profile.organizations()).hasSize(2);
        assertThat(profile.activeOrgCode()).isNull();
        assertThat(profile.activeOrgGuid()).isNull();
    }

    @Test
    void load_withEmptyGroups_usesUndefinedRole() {
        var userInfoResponse = userInfoResponse(List.of(userInfo("Иванов Иван", "ORG-CODE-1", "GUID-1")));
        when(commandFactory.buildCommand(any(GetUserDetailsRequest.class)))
                .thenReturn(Mono.just((Command<GetUserInfoResponse>) () -> Mono.just(userInfoResponse)));
        var groups = new GetUserGroupsResponse();
        when(commandFactory.buildCommand(any(GetAuthoritiesRequest.class)))
                .thenReturn(Mono.just((Command<List<String>>) () -> Mono.just(List.of("ROLE_UNDEFINED"))));

        var profile = profileService.load("ivanov").block();

        assertThat(profile.roles()).containsExactly("ROLE_UNDEFINED");
    }

    @Test
    void load_usesCache() {
        var userInfoResponse = userInfoResponse(List.of(userInfo("Иванов Иван", "ORG-CODE-1", "GUID-1")));
        when(commandFactory.buildCommand(any(GetUserDetailsRequest.class)))
                .thenReturn(Mono.just((Command<GetUserInfoResponse>) () -> Mono.just(userInfoResponse)));
        when(commandFactory.buildCommand(any(GetAuthoritiesRequest.class)))
                .thenReturn(Mono.just((Command<List<String>>) () -> Mono.just(List.of())));

        profileService.load("ivanov").block();
        profileService.load("ivanov").block();

        Mockito.verify(commandFactory, Mockito.times(1)).buildCommand(any(GetUserDetailsRequest.class));
    }

    private static GetUserInfoResponse userInfoResponse(List<TUserInfo> infos) {
        var response = new GetUserInfoResponse();
        var tUserInfos = new TUserInfos();
        tUserInfos.getUserInfo().addAll(infos);
        response.setUserInfos(tUserInfos);
        return response;
    }

    private static TUserInfo userInfo(String fullName, String orgCode, String orgGuid) {
        var employee = new TEmployee();
        employee.setLogin("ivanov");
        employee.setFullName(fullName);

        var organization = new TOrganization();
        organization.setFullName("Организация");
        organization.setSvrCode(orgCode);
        organization.setGuid(orgGuid);

        var userInfo = new TUserInfo();
        userInfo.setEmployee(employee);
        userInfo.setOrganization(organization);
        return userInfo;
    }
}
