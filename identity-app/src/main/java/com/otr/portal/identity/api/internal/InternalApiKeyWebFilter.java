package com.otr.portal.identity.api.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.springframework.util.StringUtils.hasText;

/**
 * Защита внутреннего API общим ключом (заголовок {@code X-Internal-Key}).
 * На этапе M2 — простой общий ключ из конфигурации; мTLS — харденинг позже.
 */
@Component
public class InternalApiKeyWebFilter implements WebFilter {

    public static final String API_KEY_HEADER = "X-Internal-Key";

    private final String apiKey;

    public InternalApiKeyWebFilter(@Value("${identity.internal.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        var path = request.getPath().value();
        if (!path.startsWith("/api/internal/")) {
            return chain.filter(exchange);
        }
        if (!hasText(apiKey)) {
            return unauthorized(exchange, "Internal API key не настроен на сервере");
        }
        var provided = request.getHeaders().getFirst(API_KEY_HEADER);
        if (!apiKey.equals(provided)) {
            return unauthorized(exchange, "Неверный внутренний ключ доступа");
        }
        return chain.filter(exchange);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var body = "{\"error\":\"" + message + "\"}";
        var buffer = response.bufferFactory().wrap(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
