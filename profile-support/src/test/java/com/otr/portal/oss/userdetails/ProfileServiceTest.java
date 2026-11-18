package com.otr.portal.oss.userdetails;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.otr.portal.command.Command;
import com.otr.portal.config.source.ConfigKey;
import com.otr.portal.config.source.ConfigSource;
import com.otr.portal.oss.config.OssConfig;
import com.otr.portal.oss.config.OssConfigChangedEvent;
import com.otr.portal.oss.config.UsbConfig;
import com.otr.portal.oss.config.UsbConfigChangedEvent;
import com.otr.portal.oss.exception.ProfileNotFoundException;
import com.otr.portal.oss.userdetails.command.OssCommandFactory;
import com.otr.portal.oss.userdetails.command.model.GetAuthoritiesRequest;
import com.otr.portal.oss.userdetails.command.model.GetUserDetailsRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import ru.otr.hr.ws.organization.v3.GetUserInfoResponse;
import ru.otr.hr.ws.organization.v3.TEmployee;
import ru.otr.hr.ws.organization.v3.TOrganization;
import ru.otr.hr.ws.organization.v3.TUserInfo;
import ru.otr.hr.ws.organization.v3.TUserInfos;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private final AtomicLong nanos = new AtomicLong();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ApplicationEventPublisher events = Mockito.mock(ApplicationEventPublisher.class);
    private ConfigKey<OssConfig> ossConfigKey;

    @BeforeEach
    void setUp() {
        commandFactory = Mockito.mock(OssCommandFactory.class);
        configSource = Mockito.mock(ConfigSource.class);

        ossConfigKey = ConfigKey.of("OSSConfig", OssConfig.class, raw -> null);
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
                events,
                meters,
                2,
                Caffeine.newBuilder().ticker(nanos::get).executor(Runnable::run));
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

    @Test
    void ttlCountsFromWriteAndBothConfigEventsInvalidateTheMeteredCache() {
        var info = userInfoResponse(List.of(userInfo("Иванов", "ORG", "GUID")));
        var roles = new AtomicReference<>(List.of("OLD"));
        stubDetails(() -> Mono.just(info));
        when(commandFactory.buildCommand(any(GetAuthoritiesRequest.class)))
                .thenReturn(Mono.just((Command<List<String>>) () -> Mono.just(roles.get())));

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ProfileService.class, () -> profileService);
            context.refresh();
            assertThat(profileService.load("ivanov").block().roles()).containsExactly("OLD");
            nanos.addAndGet(Duration.ofMinutes(40).toNanos());
            roles.set(List.of("NEW"));
            assertThat(profileService.load("ivanov").block().roles()).containsExactly("OLD");
            nanos.addAndGet(Duration.ofMinutes(21).toNanos());
            assertThat(profileService.load("ivanov").block().roles()).containsExactly("NEW");

            roles.set(List.of("AFTER_USB"));
            context.publishEvent(new UsbConfigChangedEvent());
            assertThat(profileService.load("ivanov").block().roles()).containsExactly("AFTER_USB");

            useSource(OssConfig.OssType.SOBI_WEB_SERVICE, 1);
            roles.set(List.of("AFTER_OSS"));
            context.publishEvent(new OssConfigChangedEvent());
            assertThat(profileService.load("ivanov").block().roles()).containsExactly("AFTER_OSS");
            roles.set(List.of("AFTER_SHORT_TTL"));
            nanos.addAndGet(Duration.ofMinutes(2).toNanos());
            assertThat(profileService.load("ivanov").block().roles()).containsExactly("AFTER_SHORT_TTL");
            assertThat(meters.get("cache.gets").tag("result", "miss").functionCounter().count()).isEqualTo(5);
        }
    }

    @Test
    void cacheHonorsItsEntryLimit() {
        when(commandFactory.buildCommand(any(GetUserDetailsRequest.class))).thenAnswer(invocation -> {
            GetUserDetailsRequest request = invocation.getArgument(0);
            var info = userInfo("Тест", "ORG", "GUID");
            info.getEmployee().setLogin(request.getUsername());
            Command<GetUserInfoResponse> command = () -> Mono.just(userInfoResponse(List.of(info)));
            return Mono.just(command);
        });
        when(commandFactory.buildCommand(any(GetAuthoritiesRequest.class)))
                .thenReturn(Mono.just((Command<List<String>>) () -> Mono.just(List.of())));

        for (String username : List.of("a", "b", "c", "d")) {
            assertThat(profileService.load(username).block().login()).isEqualTo(username);
        }
        assertThat(meters.get("cache.size").gauge().value()).isEqualTo(2);
    }

    @Test
    void invalidationDetachesInFlightLoadFromSubsequentReaders() {
        var delayed = Sinks.<GetUserInfoResponse>one();
        stubDetails(delayed::asMono);
        when(commandFactory.buildCommand(any(GetAuthoritiesRequest.class)))
                .thenReturn(Mono.just((Command<List<String>>) () -> Mono.just(List.of())));
        var oldRead = profileService.load("ivanov").toFuture();
        profileService.invalidateAll();
        var current = userInfoResponse(List.of(userInfo("Новые данные", "ORG", "GUID")));
        stubDetails(() -> Mono.just(current));
        assertThat(profileService.load("ivanov").block().fullName()).isEqualTo("Новые данные");
        delayed.tryEmitValue(userInfoResponse(List.of(userInfo("Старые данные", "ORG", "GUID"))));
        assertThat(oldRead.join().fullName()).isEqualTo("Старые данные");
        assertThat(profileService.load("ivanov").block().fullName()).isEqualTo("Новые данные");
    }

    @Test
    void wrongSubjectInAnyOrganizationCannotBePublishedOrCached() {
        var first = userInfo("Иванов", "ORG", "GUID");
        var second = userInfo("Другой", "OTHER", "GUID2");
        second.getEmployee().setLogin("Ivanov");
        var response = userInfoResponse(List.of(first, second));
        stubDetails(() -> Mono.just(response));
        when(commandFactory.buildCommand(any(GetAuthoritiesRequest.class)))
                .thenReturn(Mono.just((Command<List<String>>) () -> Mono.just(List.of())));

        assertThatThrownBy(() -> profileService.load("ivanov").block()).isInstanceOf(ProfileNotFoundException.class);
        assertThatThrownBy(() -> profileService.getOrganizations("ivanov").block())
                .isInstanceOf(ProfileNotFoundException.class);
        Mockito.verifyNoInteractions(events);

        second.getEmployee().setLogin("ivanov");
        assertThat(profileService.load("ivanov").block().organizations()).hasSize(2);
    }

    @Test
    void usbMatchesOnlyTheCanonicalSamAccountName() {
        useSource(OssConfig.OssType.USB, 1);
        var info = userInfo("Иванов", "ORG", "GUID");
        var response = userInfoResponse(List.of(info));
        stubDetails(() -> Mono.just(response));
        assertThatThrownBy(() -> profileService.loadWithoutCache("ivanov").block())
                .isInstanceOf(ProfileNotFoundException.class);
        info.getEmployee().setSamAccountName("Ivanov");
        assertThatThrownBy(() -> profileService.load("ivanov").block()).isInstanceOf(ProfileNotFoundException.class);
        Mockito.verifyNoInteractions(events);
        info.getEmployee().setSamAccountName("ivanov");
        assertThat(profileService.load("ivanov").block().login()).isEqualTo("ivanov");
    }

    private void useSource(OssConfig.OssType type, int ttlMinutes) {
        when(configSource.get(ossConfigKey)).thenReturn(Mono.just(new OssConfig(type, "http://oss", null,
                ttlMinutes, "X-Auth", true, null, null, null, null, null, null, null)));
    }

    private void stubDetails(Command<GetUserInfoResponse> command) {
        when(commandFactory.buildCommand(any(GetUserDetailsRequest.class))).thenReturn(Mono.just(command));
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
