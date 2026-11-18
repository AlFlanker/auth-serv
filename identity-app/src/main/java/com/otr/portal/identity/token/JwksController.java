package com.otr.portal.identity.token;

import com.otr.portal.token.JwtKeyProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Публичные ключи подписи JWT для portal-edge и других сервисов.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JwtKeyProvider jwtKeyProvider;

    @GetMapping("/.well-known/jwks.json")
    public Mono<Map<String, Object>> jwks() {
        return Mono.fromCallable(() -> jwtKeyProvider.publicJwkSet().toJSONObject());
    }
}
