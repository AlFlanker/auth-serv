package com.otr.portal.identity.api.profile;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Аутентификация публичных профильных эндпоинтов по Bearer JWT identity-service
 * (portal-edge проксирует {@code Authorization} как есть). Проверка — автономная,
 * по подписи и сроку действия (как на portal-edge), без обращения к реестру jti.
 * Пользователь кладётся в exchange под ключом {@link CurrentUserResolver#USER_ATTRIBUTE}.
 */
@Component
public class JwtAuthenticationWebFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private static final List<PathPattern> PROTECTED_PATHS = List.of(
            PathPatternParser.defaultInstance.parse("/user/current/**"),
            PathPatternParser.defaultInstance.parse("/currentuser"),
            PathPatternParser.defaultInstance.parse("/admin/currentuser"));

    private final JwtDecoder jwtDecoder;

    public JwtAuthenticationWebFilter(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().pathWithinApplication();
        if (PROTECTED_PATHS.stream().noneMatch(pattern -> pattern.matches(path))) {
            return chain.filter(exchange);
        }
        var authorization = exchange.getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.size() != 1) {
            return unauthorized(exchange);
        }
        var value = authorization.getFirst();
        if (value == null || !value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
                || value.length() <= BEARER_PREFIX.length()) {
            return unauthorized(exchange);
        }
        return Mono.fromCallable(() -> jwtDecoder.decode(value.substring(BEARER_PREFIX.length())))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(jwt -> {
                    exchange.getAttributes()
                            .put(CurrentUserResolver.USER_ATTRIBUTE, AuthenticatedUser.from(jwt));
                    return chain.filter(exchange);
                })
                .onErrorResume(JwtException.class, ignored -> unauthorized(exchange));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var buffer = response.bufferFactory()
                .wrap("{\"error\":\"Не авторизован\"}".getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
