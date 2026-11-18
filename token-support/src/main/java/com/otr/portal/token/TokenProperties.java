package com.otr.portal.token;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "identity.token")
public record TokenProperties(
        Duration ttl,
        String issuer,
        String keyId,
        List<String> audiences,
        Duration clockSkew,
        Duration maxSessionLifetime
) {
    public TokenProperties {
        ttl = ttl == null ? Duration.ofMinutes(30) : ttl;
        issuer = issuer == null ? "https://identity-service" : issuer;
        keyId = keyId == null ? "identity-1" : keyId;
        audiences = audiences == null ? List.of("portal-edge") : List.copyOf(audiences);
        clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
        maxSessionLifetime = maxSessionLifetime == null ? Duration.ofHours(8) : maxSessionLifetime;

        var issuerUri = URI.create(issuer);
        Assert.isTrue(("https".equalsIgnoreCase(issuerUri.getScheme()) || "http".equalsIgnoreCase(issuerUri.getScheme()))
                        && issuerUri.getHost() != null && issuerUri.getUserInfo() == null
                        && issuerUri.getQuery() == null && issuerUri.getFragment() == null,
                "identity.token.issuer must be an absolute issuer URL");
        Assert.hasText(keyId, "identity.token.key-id must have text");
        Assert.isTrue(!audiences.isEmpty() && audiences.stream().noneMatch(String::isBlank),
                "identity.token.audiences must contain nonblank recipients");
        Assert.isTrue(ttl.toSeconds() >= 1 && ttl.getNano() == 0,
                "identity.token.ttl must be a positive whole number of seconds");
        Assert.isTrue(maxSessionLifetime.compareTo(ttl) >= 0 && maxSessionLifetime.getNano() == 0,
                "identity.token.max-session-lifetime must cover at least one access token");
        Assert.isTrue(!clockSkew.isNegative() && clockSkew.compareTo(ttl) < 0,
                "identity.token.clock-skew must be nonnegative and shorter than ttl");
    }
}
