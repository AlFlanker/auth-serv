package com.otr.portal.token;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TokenProperties.class, JwtKeyProperties.class})
public class TokenConfiguration {

    @Bean
    JwtKeyProvider jwtKeyProvider(TokenProperties properties, JwtKeyProperties keys) {
        return new JwtKeyProvider(properties, keys);
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(JwtKeyProvider keyProvider) {
        return new ImmutableJWKSet<>(keyProvider.fullJwkSet());
    }

    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtKeyProvider keyProvider, TokenProperties properties) {
        var publicKeys = new ImmutableJWKSet<SecurityContext>(keyProvider.publicJwkSet());
        var decoder = NimbusJwtDecoder.withJwkSource(publicKeys)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .jwtProcessorCustomizer(processor -> {
                    var selector = processor.getJWSKeySelector();
                    processor.setJWSKeySelector((header, context) -> header.getKeyID() == null
                            ? List.of() : selector.selectJWSKeys(header, context));
                    processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(
                            new JOSEObjectType(TokenService.ACCESS_TOKEN_TYPE)));
                    processor.setJWTClaimsSetVerifier(new AccessTokenValidator(properties));
                })
                .build();
        decoder.setJwtValidator(new JwtTimestampValidator(properties.clockSkew()));
        return decoder;
    }

    @Bean
    TokenService tokenService(JwtEncoder jwtEncoder, TokenProperties properties) {
        return new TokenService(jwtEncoder, properties);
    }
}
