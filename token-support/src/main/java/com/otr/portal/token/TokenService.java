package com.otr.portal.token;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Выпуск access-токенов с единым форматом claims (username, roles, org_guid/org_code, auth_source).
 */
@RequiredArgsConstructor
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final TokenProperties properties;

    public Mono<String> mint(TokenClaims claims) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.ttl());

        var jwtClaims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(claims.username())
                .claim("username", claims.username())
                .claim("roles", claims.roles() == null ? java.util.List.of() : claims.roles())
                .claim("auth_source", claims.authSource())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString());

        if (claims.orgGuid() != null) {
            jwtClaims.claim("org_guid", claims.orgGuid());
        }
        if (claims.orgCode() != null) {
            jwtClaims.claim("org_code", claims.orgCode());
        }

        var headers = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(properties.keyId())
                .build();

        return Mono.fromCallable(() ->
                jwtEncoder.encode(JwtEncoderParameters.from(headers, jwtClaims.build()))
                        .getTokenValue());
    }
}
