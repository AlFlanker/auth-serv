package com.otr.portal.identity.users;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Хранилище локальных ролей (аналог {@code RoleManagementService}/user-role-storage монолита).
 * Чтение ролей для внутреннего и публичного API; CRUD-админка — позже.
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final JdbcTemplate jdbcTemplate;

    public List<Role> getRoles() {
        return jdbcTemplate.query(
                "SELECT id, name FROM roles ORDER BY id",
                (rs, rowNum) -> new Role(rs.getString("id"), rs.getString("name")));
    }

    public Optional<Role> getRole(String id) {
        var roles = jdbcTemplate.query(
                "SELECT id, name FROM roles WHERE id = ?",
                (rs, rowNum) -> new Role(rs.getString("id"), rs.getString("name")),
                id);
        return roles.stream().findFirst();
    }
}
