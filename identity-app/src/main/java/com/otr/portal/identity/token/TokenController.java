package com.otr.portal.identity.token;

import com.otr.portal.identity.users.LocalUserService;
import com.otr.portal.oss.exception.ProfileNotFoundException;
import com.otr.portal.oss.model.Profile;
import com.otr.portal.oss.userdetails.ProfileService;
import com.otr.portal.token.TokenClaims;
import com.otr.portal.token.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    private final AuthenticationProperties authentication;

    @PostMapping("/api/v1/token")
    public Mono<ResponseEntity<TokenView>> token(ServerWebExchange exchange,
                                                 @RequestBody(required = false) TokenRequest body) {
        return ssoPrincipalExtractor.extract(exchange)
                .flatMap(principal -> loadSsoClaims(principal.login(), principal.headerName())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED))))
                .switchIfEmpty(Mono.defer(() -> authenticatePassword(body)))
                .flatMap(tokenService::issue)
                .flatMap(issued -> tokenRegistry.register(issued.jti(), issued.expiresAt()).thenReturn(issued))
                .map(this::tokenResponse);
    }

    @PostMapping("/api/internal/token/exchange")
    public Mono<ResponseEntity<TokenView>> exchange(ServerWebExchange exchange) {
        return authenticatedBearer(exchange)
                .filterWhen(jwt -> tokenRegistry.isActive(jwt.getId()))
                .flatMap(jwt -> refreshClaims(jwt)
                        .flatMap(claims -> tokenService.reissue(claims, jwt))
                        .filterWhen(issued -> tokenRegistry.exchange(jwt.getId(), issued.jti(), issued.expiresAt())))
                .map(this::tokenResponse)
                .onErrorResume(BadJwtException.class, ignored -> Mono.empty())
                .switchIfEmpty(Mono.just(unauthorizedBearer()));
    }

    @PostMapping("/api/v1/logout")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        return authenticatedBearer(exchange)
                .flatMap(jwt -> tokenRegistry.revoke(jwt.getId()).thenReturn(ResponseEntity.noContent().<Void>build()))
                .switchIfEmpty(Mono.just(unauthorizedBearer()));
    }

    private Mono<TokenClaims> authenticatePassword(TokenRequest body) {
        if (!authentication.localPasswordEnabled()) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        }
        if (body == null || body.username() == null || body.password() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не заданы логин/пароль"));
        }
        return Mono.fromCallable(() -> localUserService.verify(body.username(), body.password()))
                .subscribeOn(Schedulers.boundedElastic())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                .map(roles -> new TokenClaims(body.username(), roles, null, null, AUTH_SOURCE_PASSWORD));
    }

    private Mono<TokenClaims> refreshClaims(Jwt jwt) {
        String username = jwt.getSubject();
        String source = jwt.getClaimAsString("auth_source");
        if (AUTH_SOURCE_PASSWORD.equals(source)) {
            if (!authentication.localPasswordEnabled()) {
                return Mono.empty();
            }
            return Mono.fromCallable(() -> localUserService.authorities(username))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(roles -> new TokenClaims(username, roles, null, null, source));
        }
        return loadSsoClaims(username, source);
    }

    private Mono<TokenClaims> loadSsoClaims(String username, String source) {
        return profileService.loadWithoutCache(username)
                .filter(profile -> username.equals(profile.login()))
                .map(profile -> toClaims(profile, source))
                .onErrorResume(ProfileNotFoundException.class, ignored -> Mono.empty());
    }

    private TokenClaims toClaims(Profile profile, String authSource) {
        return new TokenClaims(profile.login(), profile.roles(), profile.activeOrgGuid(), profile.activeOrgCode(), authSource);
    }

    private Mono<Jwt> authenticatedBearer(ServerWebExchange exchange) {
        var headers = exchange.getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (headers == null || headers.size() != 1) {
            return Mono.empty();
        }
        String authorization = headers.getFirst();
        if (!authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
                || authorization.length() <= BEARER_PREFIX.length()) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> jwtDecoder.decode(authorization.substring(BEARER_PREFIX.length())))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(BadJwtException.class, ignored -> Mono.empty());
    }

    private ResponseEntity<TokenView> tokenResponse(TokenService.IssuedToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(new TokenView(token.value(), "Bearer", token.expiresInSeconds()));
    }

    private <T> ResponseEntity<T> unauthorizedBearer() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").build();
    }

    public record TokenRequest(String username, String password) {
        @Override
        public String toString() {
            return "TokenRequest[redacted]";
        }
    }

    public record TokenView(String accessToken, String tokenType, long expiresInSeconds) {
        @Override
        public String toString() {
            return "TokenView[redacted]";
        }
    }
}
