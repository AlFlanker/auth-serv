package com.otr.portal.identity.api.profile;

import com.otr.portal.identity.users.BuiltinRoles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * HEAD-эндпоинты текущего пользователя — перенос {@code CurrentUserController}
 * монолита (core/security): заголовок {@code user} и no-cache заголовки.
 */
@RestController
public class CurrentUserController {

    private final CurrentUserResolver currentUserResolver;

    public CurrentUserController(CurrentUserResolver currentUserResolver) {
        this.currentUserResolver = currentUserResolver;
    }

    @RequestMapping(value = "/currentuser", method = RequestMethod.HEAD)
    public Mono<ResponseEntity<Void>> currentUser(ServerWebExchange exchange) {
        return currentUserResolver.requireUser(exchange)
                .map(user -> noCache(ResponseEntity.ok().header("user", user.login())).build());
    }

    @RequestMapping(value = "/admin/currentuser", method = RequestMethod.HEAD)
    public Mono<ResponseEntity<Void>> currentAdminUser(ServerWebExchange exchange) {
        return currentUserResolver.requireUser(exchange)
                .map(user -> user.hasRole(BuiltinRoles.ADMIN_ROLE)
                        ? noCache(ResponseEntity.ok().header("user", user.login())).build()
                        : ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }

    private ResponseEntity.BodyBuilder noCache(ResponseEntity.BodyBuilder builder) {
        return builder.header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0");
    }
}
