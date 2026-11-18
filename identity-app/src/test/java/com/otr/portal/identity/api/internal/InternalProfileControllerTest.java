package com.otr.portal.identity.api.internal;

import com.otr.portal.oss.exception.ProfileNotFoundException;
import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.model.Profile;
import com.otr.portal.oss.userdetails.ProfileService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * Тесты {@link InternalProfileController}: защита API-key, body-контракт и wire-формат ответов
 * (имена полей camelCase — контракт с монолитным маппером fk-portal).
 */
class InternalProfileControllerTest {

    private static final String API_KEY = "test-key";

    private final ProfileService profileService = Mockito.mock(ProfileService.class);
    private final InternalProfileController controller = new InternalProfileController(profileService);

    private final WebTestClient client = WebTestClient.bindToController(controller)
            .controllerAdvice(new InternalApiExceptionHandler())
            .webFilter(new InternalApiKeyWebFilter(API_KEY))
            .build();

    @Test
    void profile_withoutApiKey_returns401() {
        client.post().uri("/api/internal/v1/users/profile")
                .contentType(APPLICATION_JSON)
                .bodyValue(new UserRequest("ivanov", false))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void profile_withWrongApiKey_returns401() {
        client.post().uri("/api/internal/v1/users/profile")
                .header("X-Internal-Key", "wrong")
                .contentType(APPLICATION_JSON)
                .bodyValue(new UserRequest("ivanov", false))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void profile_withApiKey_returnsProfileWithWireContractFields() {
        var profile = new Profile(
                "ivanov", "Иванов Иван", "Департамент", "Инженер", "+7", "+7-2", "123",
                "ivanov@example.ru", "ivanov", "ACC-GUID", "Организация", "Организация",
                List.of("ROLE_USER"),
                List.of(new Organization("Организация", "Организация", "ORG1", "GUID-1",
                        "INN", "KPP", "OGRN", "TOFK", null)),
                "ORG1", "GUID-1", false);
        when(profileService.load("ivanov")).thenReturn(Mono.just(profile));

        client.post().uri("/api/internal/v1/users/profile")
                .header("X-Internal-Key", API_KEY)
                .contentType(APPLICATION_JSON)
                .bodyValue(new UserRequest("ivanov", false))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.login").isEqualTo("ivanov")
                .jsonPath("$.fullName").isEqualTo("Иванов Иван")
                .jsonPath("$.departmentName").isEqualTo("Департамент")
                .jsonPath("$.titleName").isEqualTo("Инженер")
                .jsonPath("$.phoneWorkplace").isEqualTo("+7")
                .jsonPath("$.phoneOther").isEqualTo("+7-2")
                .jsonPath("$.snils").isEqualTo("123")
                .jsonPath("$.email").isEqualTo("ivanov@example.ru")
                .jsonPath("$.samAccountName").isEqualTo("ivanov")
                .jsonPath("$.accountGuid").isEqualTo("ACC-GUID")
                .jsonPath("$.organizationName").isEqualTo("Организация")
                .jsonPath("$.orgFullName").isEqualTo("Организация")
                .jsonPath("$.roles[0]").isEqualTo("ROLE_USER")
                .jsonPath("$.organizations[0].organizationFullName").isEqualTo("Организация")
                .jsonPath("$.organizations[0].organizationName").isEqualTo("Организация")
                .jsonPath("$.organizations[0].orgSvrCode").isEqualTo("ORG1")
                .jsonPath("$.organizations[0].orgGUID").isEqualTo("GUID-1")
                .jsonPath("$.organizations[0].orgINN").isEqualTo("INN")
                .jsonPath("$.organizations[0].orgKPP").isEqualTo("KPP")
                .jsonPath("$.organizations[0].orgOGRN").isEqualTo("OGRN")
                .jsonPath("$.organizations[0].orgTOFK").isEqualTo("TOFK")
                .jsonPath("$.activeOrgCode").isEqualTo("ORG1")
                .jsonPath("$.activeOrgGuid").isEqualTo("GUID-1")
                .jsonPath("$.cacheHit").isEqualTo(false);
    }

    @Test
    void profile_withBypassCache_usesLoadWithoutCache() {
        var profile = new Profile(
                "ivanov", null, null, null, null, null, null, null, "ivanov", null, null, null,
                List.of(), List.of(), null, null, false);
        when(profileService.loadWithoutCache("ivanov")).thenReturn(Mono.just(profile));

        client.post().uri("/api/internal/v1/users/profile")
                .header("X-Internal-Key", API_KEY)
                .contentType(APPLICATION_JSON)
                .bodyValue(new UserRequest("ivanov", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.login").isEqualTo("ivanov")
                .jsonPath("$.cacheHit").isEqualTo(false);

        verify(profileService).loadWithoutCache("ivanov");
        verify(profileService, never()).load(anyString());
    }

    @Test
    void profile_unknownUser_returns404() {
        when(profileService.load(anyString()))
                .thenReturn(Mono.error(new ProfileNotFoundException("nobody")));

        client.post().uri("/api/internal/v1/users/profile")
                .header("X-Internal-Key", API_KEY)
                .contentType(APPLICATION_JSON)
                .bodyValue(new UserRequest("nobody", false))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void groups_withApiKey_returnsRoles() {
        when(profileService.getUserAuthorities("ivanov"))
                .thenReturn(Mono.just(List.of("ROLE_ADMIN")));

        client.post().uri("/api/internal/v1/users/groups")
                .header("X-Internal-Key", API_KEY)
                .contentType(APPLICATION_JSON)
                .bodyValue(new UserRequest("ivanov", false))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0]").isEqualTo("ROLE_ADMIN");
    }

    @Test
    void organizations_withApiKey_returnsOrganizations() {
        when(profileService.getOrganizations("ivanov"))
                .thenReturn(Mono.just(List.of(new Organization("Организация", "Организация", "ORG1",
                        "GUID-1", null, null, null, null, null))));

        client.post().uri("/api/internal/v1/users/organizations")
                .header("X-Internal-Key", API_KEY)
                .contentType(APPLICATION_JSON)
                .bodyValue(new UserRequest("ivanov", false))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].orgSvrCode").isEqualTo("ORG1")
                .jsonPath("$[0].orgGUID").isEqualTo("GUID-1");
    }

    @Test
    void updateCustomAttributes_withApiKey_returns204() {
        when(profileService.updateCustomFields("ivanov", java.util.Map.of("phone", "+7")))
                .thenReturn(Mono.empty());

        client.post().uri("/api/internal/v1/users/custom-attributes")
                .header("X-Internal-Key", API_KEY)
                .contentType(APPLICATION_JSON)
                .bodyValue(new UpdateCustomAttributesRequest("ivanov", java.util.Map.of("phone", "+7")))
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void cacheInvalidate_withApiKey_returns204AndInvalidatesAll() {
        client.post().uri("/api/internal/v1/users/cache/invalidate")
                .header("X-Internal-Key", API_KEY)
                .exchange()
                .expectStatus().isNoContent();

        verify(profileService).invalidateAll();
    }

    @Test
    void cacheInvalidate_withoutApiKey_returns401() {
        client.post().uri("/api/internal/v1/users/cache/invalidate")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(profileService, never()).invalidateAll();
    }
}
