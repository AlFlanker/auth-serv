package com.otr.portal.oss.backend;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.otr.portal.oss.config.UsbConfig;
import com.otr.portal.oss.config.UsbProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import ru.otr.hr.ws.organization.v3.GetUserGroupsRequest;
import ru.otr.hr.ws.organization.v3.GetUserInfoRequest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест {@link UsbRestBackend}: контракт USS v2 и перепаковка в формат ОШС.
 */
class UsbRestBackendTest {

    private static WireMockServer uss;

    private static UsbRestBackend backend;

    @BeforeAll
    static void setUp() {
        uss = new WireMockServer(wireMockConfig().dynamicPort());
        uss.start();
        var baseUrl = "http://localhost:" + uss.port();
        backend = new UsbRestBackend(
                WebClient.builder().build(),
                baseUrl,
                new UsbConfig(true, UsbConfig.ApiVersion.V2, null, null),
                new UsbProperties(null, null, null));
    }

    @AfterAll
    static void tearDown() {
        if (uss != null) {
            uss.stop();
        }
    }

    @Test
    void getUserInfoV2_mapsToOssFormat() {
        uss.stubFor(post(urlEqualTo("/identification/v2/employee/findByJwt?application_code=ETD_PORTAL"))
                .withRequestBody(equalToJson("{\"jwt\":\"ivanov\",\"include_roles\":false}"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "login": "ivanov",
                                  "full_name": "Иванов Иван Иванович",
                                  "email": "ivanov@example.ru",
                                  "phone": "1234",
                                  "snils": "123-456-789 00",
                                  "employees": [
                                    {
                                      "position": "Начальник отдела",
                                      "organization": {
                                        "code": "ORG1",
                                        "name": "Отдел",
                                        "full_name": "Отдел полное наименование",
                                        "full_path_codes": "GUID-1"
                                      }
                                    }
                                  ]
                                }
                                """)));

        var request = new GetUserInfoRequest();
        request.setLogin("ivanov");

        var response = backend.getUserInfoV1(request).block();

        assertThat(response).isNotNull();
        var userInfo = response.getUserInfos().getUserInfo();
        assertThat(userInfo).hasSize(1);
        assertThat(userInfo.get(0).getEmployee().getFullName()).isEqualTo("Иванов Иван Иванович");
        assertThat(userInfo.get(0).getEmployee().getLogin()).isEqualTo("ivanov");
        assertThat(userInfo.get(0).getEmployee().getSamAccountName()).isEqualTo("ivanov");
        assertThat(userInfo.get(0).getEmployee().getTitleName()).isEqualTo("Начальник отдела");
        assertThat(userInfo.get(0).getOrganization().getFullName()).isEqualTo("Отдел полное наименование");
        assertThat(userInfo.get(0).getOrganization().getGuid()).isEqualTo("GUID-1");
    }

    @Test
    void getUserGroups_mapsAllowedResourcesToOssGroups() {
        uss.stubFor(post(urlEqualTo("/authorization/v2/access/resources"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"allowed_resources\":[\"ROLE_ADMIN\",\"ROLE_USER\"]}")));

        var request = new GetUserGroupsRequest();
        request.setLogin("ivanov");

        var response = backend.getUserGroupsV1(request).block();

        assertThat(response).isNotNull();
        assertThat(response.getGroup()).containsExactly("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void serverError_mapsToOssUnavailable() {
        uss.stubFor(post(urlEqualTo("/identification/v2/employee/findByJwt?application_code=ETD_PORTAL"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        var request = new GetUserInfoRequest();
        request.setLogin("ivanov");

        var result = backend.getUserInfoV1(request)
                .map(response -> (Throwable) null)
                .onErrorResume(reactor.core.publisher.Mono::just)
                .block();

        assertThat(result).isInstanceOf(com.otr.portal.oss.exception.OssUnavailableException.class);
    }

    @Test
    void manageCustomAttributes_isUnsupportedForUsb() {
        var result = backend.manageUserCustomAttributesV1(null)
                .map(response -> (Throwable) null)
                .onErrorResume(reactor.core.publisher.Mono::just)
                .block();

        assertThat(result).isInstanceOf(UnsupportedOperationException.class);
    }
}
