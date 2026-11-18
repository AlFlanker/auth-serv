package com.otr.portal.identity.users;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест контура локальных пользователей и ролей на H2:
 * схема, проверка пароля, API ролей.
 */
@SpringBootTest
@ActiveProfiles("test")
class UsersIntegrationTest {

    @Autowired
    private LocalUserService localUserService;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(applicationContext).build();
    }

    @Test
    void authenticatesOnlyProvisionedEnabledAccounts() {
        assertThat(localUserService.exists("user")).isFalse();
        assertThat(localUserService.exists("user2")).isFalse();
        var users = new JdbcUserDetailsManager(dataSource);
        var user = User.withUsername("test-admin")
                .password(passwordEncoder.encode("test-password")).roles("ADMIN").build();
        users.createUser(user);

        assertThat(localUserService.verify("test-admin", "test-password")).containsExactly("ROLE_ADMIN");
        assertThat(localUserService.verify("test-admin", "wrong")).isNull();
        assertThat(localUserService.verify("nobody", "test-password")).isNull();
        users.updateUser(User.withUserDetails(user).disabled(true).build());
        assertThat(localUserService.verify("test-admin", "test-password")).isNull();
        assertThat(localUserService.authorities("test-admin")).isNull();
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
