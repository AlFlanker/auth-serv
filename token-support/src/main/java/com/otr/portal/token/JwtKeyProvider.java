package com.otr.portal.token;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import lombok.extern.slf4j.Slf4j;

/**
 * Провайдер ключа подписи JWT. На этапе M4 ключ генерируется при старте (kid из конфигурации);
 * загрузка/ротация ключей из PEM и secret-manager — харденинг (M5+).
 */
@Slf4j
public class JwtKeyProvider {

    private final RSAKey rsaKey;
    private final JWKSet fullJwkSet;
    private final JWKSet publicJwkSet;

    public JwtKeyProvider(TokenProperties properties) {
        try {
            this.rsaKey = new RSAKeyGenerator(2048)
                    .keyID(properties.keyId())
                    .generate();
            this.fullJwkSet = new JWKSet(rsaKey);
            this.publicJwkSet = new JWKSet(rsaKey.toPublicJWK());
            log.info("Сгенерирован ключ подписи JWT с kid '{}'", properties.keyId());
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сгенерировать ключ подписи JWT", e);
        }
    }

    public RSAKey rsaKey() {
        return rsaKey;
    }

    /**
     * Полный набор ключей (с приватным) — для энкодера.
     */
    public JWKSet fullJwkSet() {
        return fullJwkSet;
    }

    /**
     * Только публичные ключи — для JWKS endpoint.
     */
    public JWKSet publicJwkSet() {
        return publicJwkSet;
    }
}
