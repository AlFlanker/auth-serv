package com.otr.portal.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки выпуска токенов (identity.token.* в application.yml).
 */
@ConfigurationProperties(prefix = "identity.token")
public record TokenProperties(
        Duration ttl,
        String issuer,
        String keyId
) {

    public TokenProperties {
        if (ttl == null) {
            ttl = Duration.ofMinutes(30);
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "https://identity-service";
        }
        if (keyId == null || keyId.isBlank()) {
            keyId = "identity-1";
        }
    }
}
