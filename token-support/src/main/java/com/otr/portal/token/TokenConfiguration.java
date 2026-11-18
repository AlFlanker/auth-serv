package com.otr.portal.token;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Конфигурация token-support: ключ, JWKSource, энкодер/декодер (RS256, JWKS-ready).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TokenProperties.class)
public class TokenConfiguration {

    @Bean
    JwtKeyProvider jwtKeyProvider(TokenProperties properties) {
        return new JwtKeyProvider(properties);
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(JwtKeyProvider keyProvider) {
        // энкодеру нужен приватный ключ для подписи
        return new ImmutableJWKSet<>(keyProvider.fullJwkSet());
    }

    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeyProvider keyProvider) {
        try {
            return NimbusJwtDecoder.withPublicKey(keyProvider.rsaKey().toRSAPublicKey()).build();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Не удалось построить JwtDecoder по публичному ключу", e);
        }
    }

    @Bean
    TokenService tokenService(JwtEncoder jwtEncoder, TokenProperties properties) {
        return new TokenService(jwtEncoder, properties);
    }
}
