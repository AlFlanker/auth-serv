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
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * Тесты {@link InternalProfileController}: защита API-key и контракты ответов.
 */
class InternalProfileControllerTest {

    private static final String API_KEY = "test-key";

    private final ProfileService profileService = Mockito.mock(ProfileService.class);
    private final InternalProfileController controller = new InternalProfileController(profileService);

    private final WebTestClient client = WebTestClient.bindToController(controller)
            .controllerAdvice(new InternalApiExceptionHandler())
            .webFilter(new InternalApiKeyWebFilter(true, API_KEY))
            .build();

    @Test
    void profile_withoutApiKey_returns401() {
        client.post().uri("/api/internal/v1/users/ivanov/profile")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void profile_withWrongApiKey_returns401() {
        client.post().uri("/api/internal/v1/users/ivanov/profile")
                .header("X-Internal-Key", "wrong")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void profile_withApiKey_returnsProfile() {
        var profile = new Profile(
                "ivanov", "Иванов Иван", null, null, null, null, null,
                "ivanov@example.ru", "ivanov", null, "Организация", "Организация",
                List.of("ROLE_USER"),
                List.of(new Organization("Организация", "Организация", "ORG1", "GUID-1",
                        null, null, null, null, null)),
                "ORG1", "GUID-1");
        when(profileService.load("ivanov")).thenReturn(Mono.just(profile));

        client.post().uri("/api/internal/v1/users/ivanov/profile")
                .header("X-Internal-Key", API_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.login").isEqualTo("ivanov")
                .jsonPath("$.roles[0]").isEqualTo("ROLE_USER")
                .jsonPath("$.activeOrgCode").isEqualTo("ORG1");
    }

    @Test
    void profile_unknownUser_returns404() {
        when(profileService.load(anyString()))
                .thenReturn(Mono.error(new ProfileNotFoundException("nobody")));

        client.post().uri("/api/internal/v1/users/nobody/profile")
                .header("X-Internal-Key", API_KEY)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void groups_withApiKey_returnsRoles() {
        when(profileService.getUserAuthorities("ivanov"))
                .thenReturn(Mono.just(List.of("ROLE_ADMIN")));

        client.post().uri("/api/internal/v1/users/ivanov/groups")
                .header("X-Internal-Key", API_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0]").isEqualTo("ROLE_ADMIN");
    }
}
