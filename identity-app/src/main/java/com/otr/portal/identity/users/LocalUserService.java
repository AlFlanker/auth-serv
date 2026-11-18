package com.otr.portal.identity.users;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/** Локальные администраторы, созданные оператором в users/authorities. */
@Service
public class LocalUserService {

    private final JdbcUserDetailsManager userDetailsManager;
    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    public LocalUserService(DataSource dataSource, PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.userDetailsManager = new JdbcUserDetailsManager(dataSource);
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public List<String> verify(String username, String password) {
        var user = enabledUser(username);
        boolean passwordMatches = passwordEncoder.matches(password, user == null ? dummyPasswordHash : user.getPassword());
        return user != null && passwordMatches ? roles(user) : null;
    }

    public boolean exists(String username) {
        return userDetailsManager.userExists(username);
    }

    /** Возвращает null, если учётная запись удалена или отключена. */
    public List<String> authorities(String username) {
        var user = enabledUser(username);
        return user == null ? null : roles(user);
    }

    private UserDetails enabledUser(String username) {
        try {
            var user = userDetailsManager.loadUserByUsername(username);
            return user.isEnabled() ? user : null;
        } catch (UsernameNotFoundException e) {
            return null;
        }
    }

    private List<String> roles(UserDetails user) {
        return user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
}
