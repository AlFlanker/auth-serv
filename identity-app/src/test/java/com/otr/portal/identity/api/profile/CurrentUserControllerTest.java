package com.otr.portal.identity.api.profile;

import com.otr.portal.identity.users.LocalUserService;
import com.otr.portal.oss.userdetails.ProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты {@link CurrentUserController}: HEAD /currentuser и /admin/currentuser —
 * заголовок {@code user}, no-cache заголовки, проверка роли администратора
 * из источника ролей (ОШС/ЮСБ для SSO, локальная БД для локального входа).
 */
class CurrentUserControllerTest {

    private final ProfileService profileService = mock(ProfileService.class);
    private final LocalUserService localUserService = mock(LocalUserService.class);

    private WebTestClient client(String authSource) {
        CurrentUserController controller = new CurrentUserController(
                new CurrentUserResolver(), profileService, localUserService);
        return WebTestClient.bindToController(controller)
                .controllerAdvice(new PublicApiExceptionHandler())
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(CurrentUserResolver.USER_ATTRIBUTE,
                            new AuthenticatedUser("ivanov", null, null, authSource));
                    return chain.filter(exchange);
                })
                .build();
    }

    @Test
    void currentUser_returnsUserHeaderWithNoCacheHeaders() {
        client("X-Auth").head().uri("/currentuser")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("user", "ivanov")
                .expectHeader().valueEquals("Cache-Control", "no-cache, no-store, must-revalidate")
                .expectHeader().valueEquals("Pragma", "no-cache")
                .expectHeader().valueEquals("Expires", "0");
    }

    @Test
    void adminCurrentUser_ssoWithAdminRole_returns200() {
        when(profileService.getUserAuthorities("ivanov"))
                .thenReturn(Mono.just(List.of("ROLE_USER", "ROLE_ADMIN")));

        client("X-Auth").head().uri("/admin/currentuser")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("user", "ivanov")
                .expectHeader().valueEquals("Cache-Control", "no-cache, no-store, must-revalidate");
    }

    @Test
    void adminCurrentUser_ssoWithoutAdminRole_returns403() {
        when(profileService.getUserAuthorities("ivanov")).thenReturn(Mono.just(List.of("ROLE_USER")));

        client("X-Auth").head().uri("/admin/currentuser")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void adminCurrentUser_localUserWithAdminRole_returns200() {
        when(localUserService.authorities("ivanov")).thenReturn(List.of("ROLE_ADMIN"));

        client("password").head().uri("/admin/currentuser")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("user", "ivanov");
    }

    @Test
    void adminCurrentUser_localUserWithoutAdminRole_returns403() {
        when(localUserService.authorities("ivanov")).thenReturn(List.of("ROLE_USER"));

        client("password").head().uri("/admin/currentuser")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void withoutAuthenticatedUser_returns401() {
        WebTestClient.bindToController(new CurrentUserController(
                        new CurrentUserResolver(), profileService, localUserService))
                .controllerAdvice(new PublicApiExceptionHandler())
                .build()
                .head().uri("/currentuser")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
