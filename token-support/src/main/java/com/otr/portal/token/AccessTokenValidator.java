package com.otr.portal.token;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.JWTClaimsSetVerifier;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/** Проверяет подписанные claims до автоматического преобразования типов Spring Security. */
final class AccessTokenValidator implements JWTClaimsSetVerifier<SecurityContext> {
    private final TokenProperties properties;

    AccessTokenValidator(TokenProperties properties) {
        this.properties = properties;
    }

    @Override
    public void verify(JWTClaimsSet claims, SecurityContext context) throws BadJWTException {
        if (!properties.issuer().equals(claims.getIssuer())
                || !new HashSet<>(properties.audiences()).equals(new HashSet<>(claims.getAudience()))) {
            throw new BadJWTException("Unexpected issuer or audience");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank() || !subject.equals(claims.getClaim("username"))
                || !(claims.getClaim("auth_source") instanceof String source) || source.isBlank()
                || claims.getJWTID() == null || claims.getJWTID().isBlank() || claims.getJWTID().length() > 64
                || !(claims.getClaim("roles") instanceof List<?> roles)
                || roles.stream().anyMatch(role -> !(role instanceof String s) || s.isBlank())) {
            throw new BadJWTException("Invalid access token identity claims");
        }
        if (claims.getIssueTime() == null || claims.getExpirationTime() == null) {
            throw new BadJWTException("Missing access token timestamps");
        }
        var issued = claims.getIssueTime().toInstant();
        var expires = claims.getExpirationTime().toInstant();
        var authenticated = parseAuthenticationTime(claims.getClaim("auth_time"));
        if (authenticated == null || authenticated.isAfter(issued)
                || issued.isAfter(Instant.now().plus(properties.clockSkew()))
                || !expires.isAfter(issued)
                || Duration.between(issued, expires).compareTo(properties.ttl()) > 0
                || Duration.between(authenticated, expires).compareTo(properties.maxSessionLifetime()) > 0) {
            throw new BadJWTException("Invalid access token lifetime");
        }
    }

    static Instant authenticationTime(Jwt token) {
        var authenticated = parseAuthenticationTime(token.getClaims().get("auth_time"));
        if (authenticated == null) {
            throw new BadJwtException("Missing or invalid authentication time");
        }
        return authenticated;
    }

    private static Instant parseAuthenticationTime(Object value) {
        if (!(value instanceof Long || value instanceof Integer)) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(((Number) value).longValue());
        } catch (DateTimeException ex) {
            return null;
        }
    }
}
