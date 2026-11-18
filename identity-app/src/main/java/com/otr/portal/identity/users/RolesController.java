package com.otr.portal.identity.users;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Collection;

/**
 * API локальных ролей (аналог {@code RolesController} монолита; чтение).
 */
@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
public class RolesController {

    private final RoleService roleService;

    @GetMapping
    public Mono<Collection<Role>> list() {
        return Mono.fromCallable(roleService::getRoles);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<Role>> get(@PathVariable String id) {
        return Mono.fromCallable(() -> roleService.getRole(id))
                .map(role -> role.map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build()));
    }
}
