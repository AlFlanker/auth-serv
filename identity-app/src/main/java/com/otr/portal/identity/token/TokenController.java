package com.otr.portal.identity.token;

import com.otr.portal.identity.users.LocalUserService;
import com.otr.portal.oss.exception.ProfileNotFoundException;
import com.otr.portal.oss.model.Profile;
import com.otr.portal.oss.userdetails.ProfileService;
import com.otr.portal.token.TokenClaims;
import com.otr.portal.token.TokenProperties;
import com.otr.portal.token.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Эндпоинты токенов: выпуск (SSO-заголовок или локальный логин/пароль),
 * продление/перевыпуск (exchange) и logout (отзыв jti).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TokenController {

    public static final String AUTH_SOURCE_PASSWORD = "password";
    private static final String BEARER_PREFIX = "Bearer ";

    private final SsoPrincipalExtractor ssoPrincipalExtractor;
    private final ProfileService profileService;
    private final LocalUserService localUserService;
    private final TokenService tokenService;
    private final TokenRegistry tokenRegistry;
    private final JwtDecoder jwtDecoder;
    private final TokenProperties tokenProperties;

    /**
     * POST /api/v1/token — выпуск access-токена.
     * Приоритет: SSO-заголовок (прод-механизм), затем локальный логин/пароль.
     */
    @PostMapping("/api/v1/token")
    public Mono<ResponseEntity<TokenView>> token(ServerWebExchange exchange,
                                                 @RequestBody(required = false) TokenRequest body) {
        return ssoPrincipalExtractor.extract(exchange)
                .flatMap(this::issueFromSso)
                .switchIfEmpty(Mono.defer(() -> issueFromPassword(body)))
                .map(token -> ResponseEntity.ok(
                        new TokenView(token, "Bearer", tokenProperties.ttl().toSeconds())));
    }

    /**
     * POST /api/internal/token/exchange — перевыпуск токена с перечитыванием ролей
     * (замена sliding-продления монолита; отозванные токены не перевыпускаются).
     */
    @PostMapping("/api/internal/token/exchange")
    public Mono<ResponseEntity<TokenView>> exchange(ServerWebExchange exchange) {
        return bearerToken(exchange)
                .flatMap(token -> decode(token)
                        .flatMap(jwt -> tokenRegistry.isRevoked(jwt.getId())
                                .flatMap(revoked -> revoked
                                        ? unauthorizedToken()
                                        : reissue(jwt))))
                .switchIfEmpty(unauthorizedToken());
    }

    /**
     * POST /api/v1/logout — отзыв текущего токена (jti в реестр).
     */
    @PostMapping("/api/v1/logout")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        Mono<ResponseEntity<Void>> unauthorized =
                Mono.just(new ResponseEntity<>(HttpStatus.UNAUTHORIZED));
        return bearerToken(exchange)
                .flatMap(token -> decode(token)
                        .flatMap(jwt -> tokenRegistry.revoke(jwt.getId())
                                .thenReturn(new ResponseEntity<Void>(HttpStatus.NO_CONTENT))))
                .switchIfEmpty(unauthorized);
    }

    private Mono<String> issueFromSso(SsoPrincipalExtractor.SsoPrincipal principal) {
        log.info("Выпуск токена по SSO-заголовку '{}' для пользователя '{}'", principal.headerName(), principal.login());
        return profileService.load(principal.login())
                .map(profile -> toClaims(profile, principal.headerName()))
                .flatMap(tokenService::mint);
    }

    private Mono<String> issueFromPassword(TokenRequest body) {
        if (body == null || body.username() == null || body.password() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не заданы логин/пароль"));
        }
        return Mono.fromCallable(() -> localUserService.verify(body.username(), body.password()))
                .subscribeOn(Schedulers.boundedElastic())
                // verify возвращает null для неверных учётных данных — fromCallable превращает null в empty
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Неверный логин или пароль")))
                .flatMap(roles -> {
                    log.info("Выпуск токена по логину/паролю для локального пользователя '{}'", body.username());
                    return tokenService.mint(new TokenClaims(
                            body.username(), roles, null, null, AUTH_SOURCE_PASSWORD));
                });
    }

    private Mono<ResponseEntity<TokenView>> reissue(Jwt jwt) {
        var username = jwt.getSubject();
        var authSource = jwt.getClaimAsString("auth_source");
        var orgGuid = jwt.getClaimAsString("org_guid");
        var orgCode = jwt.getClaimAsString("org_code");

        // роли перечитываются заново: профиль из ОШС/ЮСБ, fallback — локальные пользователи
        return profileService.getUserAuthorities(username)
                .onErrorResume(ProfileNotFoundException.class, e -> Mono.just(List.of()))
                .flatMap(profileRoles -> {
                    Mono<List<String>> roles = !profileRoles.isEmpty()
                            ? Mono.just(profileRoles)
                            : Mono.fromCallable(() -> localUserService.authorities(username))
                                    .subscribeOn(Schedulers.boundedElastic());
                    return roles
                            .flatMap(resolved -> tokenService.mint(
                                    new TokenClaims(username, resolved, orgGuid, orgCode, authSource)))
                            .map(token -> ResponseEntity.ok(
                                    new TokenView(token, "Bearer", tokenProperties.ttl().toSeconds())));
                });
    }

    private TokenClaims toClaims(Profile profile, String authSource) {
        return new TokenClaims(
                profile.login(),
                profile.roles(),
                profile.activeOrgGuid(),
                profile.activeOrgCode(),
                authSource);
    }

    private Mono<String> bearerToken(ServerWebExchange exchange) {
        var authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return Mono.empty();
        }
        return Mono.just(authorization.substring(BEARER_PREFIX.length()));
    }

    private Mono<Jwt> decode(String token) {
        try {
            return Mono.just(jwtDecoder.decode(token));
        } catch (JwtException e) {
            return Mono.empty();
        }
    }

    private Mono<ResponseEntity<TokenView>> unauthorizedToken() {
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    public record TokenRequest(String username, String password) {
    }

    public record TokenView(String accessToken, String tokenType, long expiresInSeconds) {
    }
}
