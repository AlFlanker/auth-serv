package com.otr.portal.identity.users;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест контура локальных пользователей и ролей на H2:
 * схема, bootstrap user/123, проверка пароля, API ролей.
 */
@SpringBootTest
@ActiveProfiles("test")
class UsersIntegrationTest {

    @Autowired
    private LocalUserService localUserService;

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(applicationContext).build();
    }

    @Test
    void bootstrap_createsDefaultUsersWithRoles() {
        assertThat(localUserService.exists("user")).isTrue();
        assertThat(localUserService.exists("user2")).isTrue();

        var roles = localUserService.verify("user", "123");
        assertThat(roles).contains("ROLE_ADMIN", "ROLE_USER");

        assertThat(localUserService.verify("user", "wrong")).isNull();
        assertThat(localUserService.verify("nobody", "123")).isNull();
    }

    @Test
    void rolesApi_returnsBuiltinRoles() {
        client.get().uri("/roles")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[?(@.id == 'ROLE_ADMIN')]").exists()
                .jsonPath("$[?(@.id == 'ROLE_USER')]").exists()
                .jsonPath("$[?(@.id == 'ROLE_EDITOR')]").exists();
    }

    @Test
    void rolesApi_unknownRole_returns404() {
        client.get().uri("/roles/ROLE_UNKNOWN")
                .exchange()
                .expectStatus().isNotFound();
    }
}
