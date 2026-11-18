package com.otr.portal.token;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
public class TokenService {
    static final String ACCESS_TOKEN_TYPE = "at+jwt";
    private final JwtEncoder jwtEncoder;
    private final TokenProperties properties;

    public record IssuedToken(String value, long expiresInSeconds, String jti, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedToken[expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    public Mono<String> mint(TokenClaims claims) {
        return issue(claims).map(IssuedToken::value);
    }

    public Mono<IssuedToken> issue(TokenClaims claims) {
        return Mono.fromCallable(() -> {
            var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            return sign(claims, now, now);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Обменивает ранее проверенный access-токен с сохранением времени входа. */
    public Mono<IssuedToken> reissue(TokenClaims claims, Jwt prior) {
        return Mono.fromCallable(() -> {
            var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            if (prior.getExpiresAt() == null || !now.isBefore(prior.getExpiresAt())
                    || !claims.username().equals(prior.getSubject())
                    || !claims.authSource().equals(prior.getClaimAsString("auth_source"))) {
                throw new BadJwtException("Access token cannot be exchanged");
            }
            return sign(claims, now, AccessTokenValidator.authenticationTime(prior));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private IssuedToken sign(TokenClaims claims, Instant now, Instant authenticatedAt) {
        if (claims.username() == null || claims.username().isBlank()
                || claims.authSource() == null || claims.authSource().isBlank()) {
            throw new IllegalArgumentException("Token subject and authentication source are required");
        }
        Instant sessionEnd = authenticatedAt.plus(properties.maxSessionLifetime());
        // Часы другого узла могли опережать локальные в пределах clock-skew.
        Instant issuedAt = now.isBefore(authenticatedAt) ? authenticatedAt : now;
        Instant expiresAt = now.plus(properties.ttl());
        if (sessionEnd.isBefore(expiresAt)) {
            expiresAt = sessionEnd;
        }
        if (!issuedAt.isBefore(expiresAt)) {
            throw new BadJwtException("Authentication session expired");
        }

        String jti = UUID.randomUUID().toString();
        var jwtClaims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(properties.audiences())
                .subject(claims.username())
                .claim("username", claims.username())
                .claim("roles", claims.roles() == null ? List.of() : claims.roles())
                .claim("auth_source", claims.authSource())
                .claim("auth_time", authenticatedAt.getEpochSecond())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(jti);

        if (claims.orgGuid() != null) {
            jwtClaims.claim("org_guid", claims.orgGuid());
        }
        if (claims.orgCode() != null) {
            jwtClaims.claim("org_code", claims.orgCode());
        }

        var headers = JwsHeader.with(SignatureAlgorithm.RS256)
                .type(ACCESS_TOKEN_TYPE)
                .keyId(properties.keyId())
                .build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(headers, jwtClaims.build())).getTokenValue();
        return new IssuedToken(value, Duration.between(now, expiresAt).toSeconds(), jti, expiresAt);
    }
}
