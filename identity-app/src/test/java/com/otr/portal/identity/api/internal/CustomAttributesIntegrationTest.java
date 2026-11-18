package com.otr.portal.identity.api.internal;

import com.otr.portal.command.CommandConfigRegistry;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.ResilienceRegistryConfig;
import com.otr.portal.config.source.ConfigKey;
import com.otr.portal.config.source.ConfigSource;
import com.otr.portal.oss.backend.OssBackend;
import com.otr.portal.oss.backend.OssBackendFactory;
import com.otr.portal.oss.backend.UsbRestBackend;
import com.otr.portal.oss.config.OssConfig;
import com.otr.portal.oss.config.UsbConfig;
import com.otr.portal.oss.config.UsbProperties;
import com.otr.portal.oss.connector.OssConnectionImpl;
import com.otr.portal.oss.userdetails.ProfileService;
import com.otr.portal.oss.userdetails.command.OssCommandFactory;
import com.otr.portal.soap.client.exception.SoapFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import ru.otr.hr.ws.organization.v3.GetUserInfoResponse;
import ru.otr.hr.ws.organization.v3.SimpleResponse;
import ru.otr.hr.ws.organization.v3.TEmployee;
import ru.otr.hr.ws.organization.v3.TOrganization;
import ru.otr.hr.ws.organization.v3.TUserInfo;
import ru.otr.hr.ws.organization.v3.TUserInfos;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomAttributesIntegrationTest {
    private final ConfigSource config = mock(ConfigSource.class);
    private final ConfigKey<OssConfig> ossKey = ConfigKey.of("OSSConfig", OssConfig.class, raw -> null);
    private final OssBackendFactory backends = mock(OssBackendFactory.class);
    private final OssBackend soap = mock(OssBackend.class);
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        var meter = new SimpleMeterRegistry();
        var resilience = new ResilienceRegistryConfig();
        var registry = new CommandConfigRegistry(resilience.bulkheadRegistry(meter), resilience.timeLimiterRegistry(meter),
                resilience.circuitBreakerRegistry(meter), resilience.retryRegistry(meter));
        var commands = new OssCommandFactory(new OssConnectionImpl(backends), config, ossKey,
                new CommandResilienceDecorator(registry));
        var usbKey = ConfigKey.of("UsbConfig", UsbConfig.class, raw -> null);
        var profiles = new ProfileService(commands, config, ossKey, usbKey, event -> {}, meter, 100);
        client = WebTestClient.bindToController(new InternalProfileController(profiles))
                .controllerAdvice(new InternalApiExceptionHandler())
                .webFilter(new InternalApiKeyWebFilter("test-key"))
                .build();
        configure(OssConfig.OssType.SOBI_WEB_SERVICE, "test-manage-key", soap);

        var employee = new TEmployee();
        employee.setLogin("ivanov");
        var user = new TUserInfo();
        user.setEmployee(employee);
        user.setOrganization(new TOrganization());
        var users = new TUserInfos();
        users.getUserInfo().add(user);
        var response = new GetUserInfoResponse();
        response.setUserInfos(users);
        when(soap.getUserInfoV1(any())).thenReturn(Mono.just(response));
    }

    @Test
    void confirmedWriteReturns204AndReloadsCachedProfile() {
        when(soap.manageUserCustomAttributesV1(any())).thenReturn(Mono.just(new SimpleResponse()));
        loadProfile();
        update().expectStatus().isNoContent().expectBody().isEmpty();
        loadProfile();
        verify(soap, times(2)).getUserInfoV1(any());
    }

    @Test
    void missingAcknowledgementKeepsCachedProfile() {
        when(soap.manageUserCustomAttributesV1(any())).thenReturn(Mono.empty());
        loadProfile();
        update().expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("OSS_UNAVAILABLE");
        loadProfile();
        verify(soap).getUserInfoV1(any());
    }

    @Test
    void soapFaultKeepsCachedProfile() {
        when(soap.manageUserCustomAttributesV1(any())).thenReturn(Mono.error(new SoapFaultException("Server", "fault")));
        loadProfile();
        update().expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("OSS_UNAVAILABLE");
        loadProfile();
        verify(soap).getUserInfoV1(any());
    }

    @Test
    void timeLimiterReturns504AndKeepsCachedProfile() {
        when(soap.manageUserCustomAttributesV1(any())).thenReturn(Mono.never());
        loadProfile();
        update().expectStatus().isEqualTo(504).expectBody().jsonPath("$.code").isEqualTo("TIMEOUT");
        loadProfile();
        verify(soap).getUserInfoV1(any());
    }

    @Test
    void usbWithoutSobiCredentialsReturns501() {
        var usb = new UsbRestBackend(WebClient.create(), "http://usb.test",
                new UsbConfig(null, null, null, null), new UsbProperties(null, null, null));
        configure(OssConfig.OssType.USB, null, usb);
        update().expectStatus().isEqualTo(501).expectBody().jsonPath("$.code").isEqualTo("NOT_IMPLEMENTED");
    }

    private void configure(OssConfig.OssType type, String manageKey, OssBackend backend) {
        when(config.get(ossKey)).thenReturn(Mono.just(new OssConfig(type, "http://oss.test", manageKey,
                60, "X-Auth", false, 1, 20, 1, 20, 1, 20, null)));
        when(backends.getBackend()).thenReturn(Mono.just(backend));
    }

    private void loadProfile() {
        client.post().uri("/api/internal/v1/users/ivanov/profile").header("X-Internal-Key", "test-key")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.login").isEqualTo("ivanov");
    }

    private WebTestClient.ResponseSpec update() {
        return client.post().uri("/api/internal/v1/users/ivanov/custom-attributes")
                .header("X-Internal-Key", "test-key").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("department", "finance")).exchange();
    }
}
