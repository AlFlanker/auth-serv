package com.otr.portal.identity.users;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/**
 * Конфигурация пользовательского контура: BCrypt и инициализация встроенных ролей
 * (аналог {@code initializeRoles()} монолита).
 */
@Configuration(proxyBeanMethods = false)
public class UsersConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    RoleInitializer roleInitializer(JdbcTemplate jdbcTemplate) {
        return new RoleInitializer(jdbcTemplate);
    }

    public static class RoleInitializer implements ApplicationListener<ApplicationReadyEvent> {

        private final JdbcTemplate jdbcTemplate;

        RoleInitializer(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            List<Role> builtin = List.of(
                    new Role(BuiltinRoles.ADMIN_ROLE, "Администратор"),
                    new Role(BuiltinRoles.USER_ROLE, "Пользователь"),
                    new Role(BuiltinRoles.EDITOR_ROLE, "Редактор"));
            for (Role role : builtin) {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM roles WHERE id = ?", Integer.class, role.id());
                if (count != null && count == 0) {
                    jdbcTemplate.update("INSERT INTO roles (id, name) VALUES (?, ?)", role.id(), role.name());
                }
            }
        }
    }
}
