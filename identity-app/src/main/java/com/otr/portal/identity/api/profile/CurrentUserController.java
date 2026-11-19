package com.otr.portal.identity.api.profile;

import com.otr.portal.identity.token.TokenController;
import com.otr.portal.identity.users.BuiltinRoles;
import com.otr.portal.identity.users.LocalUserService;
import com.otr.portal.oss.userdetails.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * HEAD-эндпоинты текущего пользователя — перенос {@code CurrentUserController}
 * монолита (core/security): заголовок {@code user} и no-cache заголовки.
 * Ролей в claims JWT больше нет, поэтому проверка {@code /admin/currentuser}
 * берёт роли из источника отдельным запросом: локальная БД для локального входа
 * ({@code auth_source=password}), ОШС/ЮСБ для SSO.
 */
@RestController
@RequiredArgsConstructor
public class CurrentUserController {

    private final CurrentUserResolver currentUserResolver;
    private final ProfileService profileService;
    private final LocalUserService localUserService;

    @RequestMapping(value = "/currentuser", method = RequestMethod.HEAD)
    public Mono<ResponseEntity<Void>> currentUser(ServerWebExchange exchange) {
        return currentUserResolver.requireUser(exchange)
                .map(user -> noCache(ResponseEntity.ok().header("user", user.login())).build());
    }

    @RequestMapping(value = "/admin/currentuser", method = RequestMethod.HEAD)
    public Mono<ResponseEntity<Void>> currentAdminUser(ServerWebExchange exchange) {
        return currentUserResolver.requireUser(exchange)
                .flatMap(user -> loadRoles(user)
                        .map(roles -> roles.contains(BuiltinRoles.ADMIN_ROLE)
                                ? noCache(ResponseEntity.ok().header("user", user.login())).build()
                                : ResponseEntity.status(HttpStatus.FORBIDDEN).build()));
    }

    private Mono<List<String>> loadRoles(AuthenticatedUser user) {
        if (TokenController.AUTH_SOURCE_PASSWORD.equals(user.authSource())) {
            return Mono.fromCallable(() -> localUserService.authorities(user.login()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(roles -> roles == null ? List.of() : roles);
        }
        return profileService.getUserAuthorities(user.login());
    }

    private ResponseEntity.BodyBuilder noCache(ResponseEntity.BodyBuilder builder) {
        return builder.header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0");
    }
}
