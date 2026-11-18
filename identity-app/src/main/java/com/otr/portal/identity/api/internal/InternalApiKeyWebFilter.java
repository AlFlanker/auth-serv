package com.otr.portal.identity.api.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.springframework.util.StringUtils.hasText;

/**
 * Защита внутреннего API общим ключом (заголовок {@code X-Internal-Key}).
 */
@Component
public class InternalApiKeyWebFilter implements WebFilter {

    public static final String API_KEY_HEADER = "X-Internal-Key";

    private static final PathPattern INTERNAL = PathPatternParser.defaultInstance.parse("/api/internal/**");

    private final byte[] apiKey;

    public InternalApiKeyWebFilter(@Value("${identity.internal.api-key:}") String apiKey) {
        this.apiKey = hasText(apiKey) ? apiKey.getBytes(StandardCharsets.UTF_8) : null;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        if (!INTERNAL.matches(request.getPath().pathWithinApplication())) {
            return chain.filter(exchange);
        }
        if (apiKey == null) {
            return unauthorized(exchange, "Internal API key не настроен на сервере");
        }
        var provided = request.getHeaders().get(API_KEY_HEADER);
        if (provided == null || provided.size() != 1
                || !MessageDigest.isEqual(apiKey, provided.getFirst().getBytes(StandardCharsets.UTF_8))) {
            return unauthorized(exchange, "Неверный внутренний ключ доступа");
        }
        return chain.filter(exchange);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var body = "{\"error\":\"" + message + "\"}";
        var buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
