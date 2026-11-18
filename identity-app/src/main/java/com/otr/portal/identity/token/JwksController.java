package com.otr.portal.identity.token;

import com.otr.portal.token.JwtKeyProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/** Публичные ключи подписи для portal-edge и других потребителей. */
@RestController
public class JwksController {

    private final Map<String, Object> publicKeys;
    private final CacheControl cacheControl;

    public JwksController(JwtKeyProvider provider,
                          @Value("${identity.token.jwks-cache-ttl:5m}") Duration cacheTtl) {
        if (cacheTtl.isNegative()) {
            throw new IllegalArgumentException("identity.token.jwks-cache-ttl должен быть >= 0");
        }
        publicKeys = provider.publicJwkSet().toJSONObject();
        cacheControl = CacheControl.maxAge(cacheTtl).cachePublic().mustRevalidate();
    }

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().cacheControl(cacheControl).body(publicKeys);
    }
}
