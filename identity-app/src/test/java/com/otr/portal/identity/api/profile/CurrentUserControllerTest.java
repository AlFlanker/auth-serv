package com.otr.portal.identity.api.profile;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

/**
 * Тесты {@link CurrentUserController}: HEAD /currentuser и /admin/currentuser —
 * заголовок {@code user}, no-cache заголовки, проверка роли администратора.
 */
class CurrentUserControllerTest {

    private final CurrentUserController controller = new CurrentUserController(new CurrentUserResolver());

    private WebTestClient client(String... roles) {
        return WebTestClient.bindToController(controller)
                .controllerAdvice(new PublicApiExceptionHandler())
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(CurrentUserResolver.USER_ATTRIBUTE,
                            new AuthenticatedUser("ivanov", List.of(roles), null, null, "X-Auth"));
                    return chain.filter(exchange);
                })
                .build();
    }

    @Test
    void currentUser_returnsUserHeaderWithNoCacheHeaders() {
        client("ROLE_USER").head().uri("/currentuser")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("user", "ivanov")
                .expectHeader().valueEquals("Cache-Control", "no-cache, no-store, must-revalidate")
                .expectHeader().valueEquals("Pragma", "no-cache")
                .expectHeader().valueEquals("Expires", "0");
    }

    @Test
    void adminCurrentUser_withAdminRole_returns200() {
        client("ROLE_USER", "ROLE_ADMIN").head().uri("/admin/currentuser")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("user", "ivanov")
                .expectHeader().valueEquals("Cache-Control", "no-cache, no-store, must-revalidate");
    }

    @Test
    void adminCurrentUser_withoutAdminRole_returns403() {
        client("ROLE_USER").head().uri("/admin/currentuser")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void withoutAuthenticatedUser_returns401() {
        WebTestClient.bindToController(controller)
                .controllerAdvice(new PublicApiExceptionHandler())
                .build()
                .head().uri("/currentuser")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
