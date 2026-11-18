package com.otr.portal.identity.users;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;

import static com.otr.portal.identity.users.BuiltinRoles.ADMIN_ROLE;
import static com.otr.portal.identity.users.BuiltinRoles.EDITOR_ROLE;
import static com.otr.portal.identity.users.BuiltinRoles.USER_ROLE;

/**
 * Локальные администраторы портала (аналог {@code JdbcUserDetailsManager} монолита).
 * Использует стандартную схему Spring Security {@code users}/{@code authorities}.
 */
@Slf4j
@Service
public class LocalUserService {

    public static final String DEFAULT_ADMIN_USER = "user";
    public static final String DEFAULT_ADMIN_PASSWORD = "123";

    private final JdbcUserDetailsManager userDetailsManager;
    private final PasswordEncoder passwordEncoder;

    public LocalUserService(DataSource dataSource, PasswordEncoder passwordEncoder,
                            org.flywaydb.core.Flyway flyway) {
        // зависимость от Flyway гарантирует, что миграции выполнены до работы с БД
        this.passwordEncoder = passwordEncoder;
        this.userDetailsManager = new JdbcUserDetailsManager(dataSource);
        bootstrapDefaultUsers();
    }

    /**
     * Проверяет учётные данные локального пользователя.
     *
     * @return роли пользователя или {@code null}, если логин/пароль не подошли или пользователь не существует
     */
    public List<String> verify(String username, String password) {
        final org.springframework.security.core.userdetails.UserDetails userDetails;
        try {
            userDetails = userDetailsManager.loadUserByUsername(username);
        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            return null;
        }
        if (!passwordEncoder.matches(password, userDetails.getPassword())) {
            return null;
        }
        return userDetails.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .toList();
    }

    public boolean exists(String username) {
        return userDetailsManager.userExists(username);
    }

    /**
     * Роли локального пользователя (без проверки пароля).
     */
    public List<String> authorities(String username) {
        try {
            return userDetailsManager.loadUserByUsername(username).getAuthorities().stream()
                    .map(authority -> authority.getAuthority())
                    .toList();
        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            return List.of();
        }
    }

    private void bootstrapDefaultUsers() {
        if (userDetailsManager.userExists(DEFAULT_ADMIN_USER)) {
            return;
        }
        log.info("Инициализация локальных пользователей по умолчанию (user/123, user2/1234)");
        createDefaultUsers();
    }

    private void createDefaultUsers() {
        var admin = new org.springframework.security.core.userdetails.User(
                DEFAULT_ADMIN_USER, passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD),
                true, true, true, true,
                List.of(new SimpleGrantedAuthority(ADMIN_ROLE), new SimpleGrantedAuthority(USER_ROLE)));
        var editor = new org.springframework.security.core.userdetails.User(
                "user2", passwordEncoder.encode("1234"),
                true, true, true, true,
                List.of(new SimpleGrantedAuthority(USER_ROLE), new SimpleGrantedAuthority(EDITOR_ROLE)));
        userDetailsManager.createUser(admin);
        userDetailsManager.createUser(editor);
    }
}
