package com.otr.portal.identity.api.profile;

import com.otr.portal.identity.api.internal.InternalApiExceptionHandler;
import com.otr.portal.oss.exception.ProfileNotFoundException;
import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.model.Profile;
import com.otr.portal.oss.userdetails.ProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * Тесты {@link ProfileViewController}: ветки JWT-пользователя, wire-контракт ответов,
 * cookie активной организации, ошибки 428/404/400.
 */
class ProfileViewControllerTest {

    private final ProfileService profileService = Mockito.mock(ProfileService.class);
    private final CurrentUserResolver currentUserResolver = new CurrentUserResolver();
    private final ProfileViewController controller = new ProfileViewController(profileService, currentUserResolver,
            new ActiveOrganizationResolver(), new ProfileViewMapper());

    private WebTestClient client;

    private static Profile profile(String... orgCodes) {
        List<Organization> organizations = List.of(orgCodes).stream()
                .map(code -> new Organization(code, code, code, "GUID-" + code,
                        null, null, null, null, (short) 1))
                .toList();
        return new Profile("ivanov", "Иванов Иван", "Департамент", "Инженер", "+7", "+7-2",
                "123", "ivanov@example.ru", "ivanov", "ACC-GUID", null, null,
                List.of("ROLE_USER"), organizations, null, null, false);
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(controller)
                .controllerAdvice(new PublicApiExceptionHandler(), new InternalApiExceptionHandler())
                .webFilter(authenticatedUserFilter())
                .build();
    }

    private WebFilter authenticatedUserFilter() {
        return (exchange, chain) -> {
            exchange.getAttributes().put(CurrentUserResolver.USER_ATTRIBUTE,
                    new AuthenticatedUser("ivanov", List.of("ROLE_USER"), null, null, "X-Auth"));
            return chain.filter(exchange);
        };
    }

    @Test
    void getUser_returnsMonolithWireContract() {
        when(profileService.load("ivanov")).thenReturn(Mono.just(profile("ORG1")));

        client.get().uri("/user/current/profile")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-cache")
                .expectBody()
                .jsonPath("$.employee.login.value").isEqualTo("ivanov")
                .jsonPath("$.employee.fullName.value").isEqualTo("Иванов Иван")
                .jsonPath("$.employee.organizationName.value").isEqualTo("ORG1")
                .jsonPath("$.employee.phoneWorkplace.value").isEqualTo("+7")
                .jsonPath("$.employee.phoneWorkplace.editable").isEqualTo(true)
                .jsonPath("$.employee.email.editable").isEqualTo(false)
                .jsonPath("$.organization.orgSvrCode.value").isEqualTo("ORG1")
                .jsonPath("$.organization.orgGUID.value").isEqualTo("GUID-ORG1")
                .jsonPath("$.organization.orgFullName.value").isEqualTo("ORG1");
    }

    @Test
    void getUser_multiOrgResolvesByCookie() {
        when(profileService.load("ivanov")).thenReturn(Mono.just(profile("ORG1", "ORG2")));

        client.get().uri("/user/current/profile")
                .cookie("ACTIVE_ORG", "ORG2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.employee.organizationName.value").isEqualTo("ORG2")
                .jsonPath("$.organization.orgSvrCode.value").isEqualTo("ORG2");
    }

    @Test
    void getUser_multiOrgWithoutSelection_returns428PreconditionContract() {
        when(profileService.load("ivanov")).thenReturn(Mono.just(profile("ORG1", "ORG2")));

        client.get().uri("/user/current/profile")
                .exchange()
                .expectStatus().isEqualTo(428)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("ORG_NOT_SELECTED")
                .jsonPath("$.error").isNotEmpty();
    }

    @Test
    void getUser_unknownProfile_returns404() {
        when(profileService.load("ivanov")).thenReturn(Mono.error(new ProfileNotFoundException("ivanov")));

        client.get().uri("/user/current/profile")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void getWorkPhone_usesLoadWithoutCache() {
        when(profileService.loadWithoutCache("ivanov")).thenReturn(Mono.just(profile("ORG1")));

        client.get().uri("/user/current/profile/workphone")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-cache")
                .expectBody()
                .jsonPath("$.employee.phoneWorkplace.value").isEqualTo("+7")
                .jsonPath("$.employee.phoneWorkplace.editable").isEqualTo(true);

        verify(profileService).loadWithoutCache("ivanov");
        verify(profileService, never()).load("ivanov");
    }

    @Test
    void saveUser_updatesOwnPhoneWorkplace_returns200() {
        when(profileService.updateCustomFields("ivanov", Map.of("phoneWorkplace", "+7 999")))
                .thenReturn(Mono.empty());

        client.post().uri("/user/current/profile")
                .contentType(APPLICATION_JSON)
                .bodyValue("""
                        {
                          "employee": {
                            "login": {"value": "petrov"},
                            "phoneWorkplace": {"value": "+7 999", "editable": true}
                          }
                        }
                        """)
                .exchange()
                .expectStatus().isOk();

        // Обновляется только владелец JWT: логин из тела игнорируется.
        verify(profileService).updateCustomFields("ivanov", Map.of("phoneWorkplace", "+7 999"));
    }

    @Test
    void saveUser_missingPhoneWorkplace_returns400() {
        client.post().uri("/user/current/profile")
                .contentType(APPLICATION_JSON)
                .bodyValue("""
                        {"employee": {"login": {"value": "ivanov"}}}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("BAD_REQUEST");

        verify(profileService, never()).updateCustomFields(Mockito.anyString(), Mockito.anyMap());
    }

    @Test
    void getOrganizations_returnsReorganizationViews() {
        when(profileService.getOrganizations("ivanov")).thenReturn(Mono.just(List.of(
                new Organization("Организация", "Организация", "ORG1", null, null, null, null, null, (short) 1),
                new Organization("Реорганизация", "Реорганизация", "ORG2", null, null, null, null, null, (short) 4))));

        client.get().uri("/user/current/profile/organizations")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].organizationCode").isEqualTo("ORG1")
                .jsonPath("$[0].organizationName").isEqualTo("Организация")
                .jsonPath("$[0].active").isEqualTo(true)
                .jsonPath("$[1].organizationCode").isEqualTo("ORG2")
                .jsonPath("$[1].active").isEqualTo(false);
    }

    @Test
    void getOrganizationsActive_resolvesFromCookie() {
        when(profileService.load("ivanov")).thenReturn(Mono.just(profile("ORG1", "ORG2")));

        client.get().uri("/user/current/profile/organizations/active")
                .cookie("ACTIVE_ORG", "ORG2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.organizationCode").isEqualTo("ORG2")
                .jsonPath("$.active").isEqualTo(true);
    }

    @Test
    void getOrganizationsActive_withoutSelection_returns428() {
        when(profileService.load("ivanov")).thenReturn(Mono.just(profile("ORG1", "ORG2")));

        client.get().uri("/user/current/profile/organizations/active")
                .exchange()
                .expectStatus().isEqualTo(428)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("ORG_NOT_SELECTED");
    }

    @Test
    void putOrganizationsActive_setsActiveOrgCookie() {
        when(profileService.load("ivanov")).thenReturn(Mono.just(profile("ORG1", "ORG2")));

        client.put().uri("/user/current/profile/organizations/active")
                .contentType(APPLICATION_JSON)
                .bodyValue("""
                        {"organizationCode": "ORG2", "organizationName": "ORG2"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches(HttpHeaders.SET_COOKIE,
                        ".*ACTIVE_ORG=ORG2; Path=/; HttpOnly; SameSite=Strict.*");
    }

    @Test
    void putOrganizationsActive_unknownCode_returns404() {
        when(profileService.load("ivanov")).thenReturn(Mono.just(profile("ORG1", "ORG2")));

        client.put().uri("/user/current/profile/organizations/active")
                .contentType(APPLICATION_JSON)
                .bodyValue("""
                        {"organizationCode": "FOREIGN", "organizationName": "FOREIGN"}
                        """)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("ORGANIZATION_NOT_FOUND");
    }

    @Test
    void putOrganizationsActive_blankCode_returns400() {
        when(profileService.load("ivanov")).thenReturn(Mono.just(profile("ORG1", "ORG2")));

        client.put().uri("/user/current/profile/organizations/active")
                .contentType(APPLICATION_JSON)
                .bodyValue("""
                        {"organizationCode": "", "organizationName": ""}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("BAD_REQUEST");
    }

    @Test
    void profileEndpoints_withoutAuthenticatedUser_return401() {
        var unauthenticated = WebTestClient.bindToController(controller)
                .controllerAdvice(new PublicApiExceptionHandler(), new InternalApiExceptionHandler())
                .build();

        unauthenticated.get().uri("/user/current/profile")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }
}
