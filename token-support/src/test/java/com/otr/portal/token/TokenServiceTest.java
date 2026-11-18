package com.otr.portal.token;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenServiceTest {
    private final TokenProperties properties = new TokenProperties(Duration.ofMinutes(30),
            "https://identity-service.test", "test-kid", List.of("edge"), Duration.ofSeconds(5), Duration.ofHours(2));
    private final TokenClaims user = new TokenClaims("ivanov", List.of("ROLE_USER"), "GUID-1", "ORG-1", "X-Auth");
    private JwtKeyProvider keys;
    private TokenService service;
    private JwtDecoder decoder;

    @BeforeAll
    void setUp() {
        keys = new JwtKeyProvider(properties, new JwtKeyProperties(null, Map.of(), true));
        service = new TokenService(new NimbusJwtEncoder(new ImmutableJWKSet<>(keys.fullJwkSet())), properties);
        decoder = new TokenConfiguration().jwtDecoder(keys, properties);
    }

    @Test
    void issueAndVerify() {
        var token = service.issue(user).block();
        assertThat(token.expiresInSeconds()).isEqualTo(properties.ttl().toSeconds());
        var jwt = decoder.decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo(user.username());
        assertThat(jwt.getClaimAsString("username")).isEqualTo(user.username());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_USER");
        assertThat(jwt.getClaimAsString("org_guid")).isEqualTo("GUID-1");
        assertThat(jwt.getClaimAsString("org_code")).isEqualTo("ORG-1");
        assertThat(jwt.getClaimAsString("auth_source")).isEqualTo("X-Auth");
        assertThat(jwt.getIssuer().toString()).isEqualTo(properties.issuer());
        assertThat(jwt.getAudience()).isEqualTo(properties.audiences());
        assertThat(token.jti()).isNotBlank().isEqualTo(jwt.getId());
        assertThat(token.expiresAt()).isEqualTo(jwt.getExpiresAt());
        assertThat(AccessTokenValidator.authenticationTime(jwt)).isEqualTo(jwt.getIssuedAt());
        assertThat(jwt.getHeaders()).containsEntry("kid", "test-kid").containsEntry("typ", "at+jwt");
    }

    @Test
    void mintWithoutOrganization() {
        var jwt = decoder.decode(service.mint(new TokenClaims(
                "admin", List.of("ROLE_ADMIN"), null, null, "password")).block());
        assertThat(jwt.getClaims()).doesNotContainKeys("org_guid", "org_code");
        assertThat(jwt.getClaimAsString("auth_source")).isEqualTo("password");
    }

    @Test
    void rejectsTokensWithUntrustedHeaders() throws Exception {
        assertInvalid(header(JWSAlgorithm.RS512, "at+jwt", "test-kid"), claims());
        assertInvalid(header(JWSAlgorithm.RS256, "JWT", "test-kid"), claims());
        assertInvalid(header(JWSAlgorithm.RS256, "at+jwt", "unknown"), claims());
        assertInvalid(header(JWSAlgorithm.RS256, "at+jwt", null), claims());
    }

    @Test
    void verifiesRetainedTokensUntilKeyRemoval(@TempDir Path directory) throws Exception {
        String beforeRotation = service.issue(user).block().value();
        var retainedPublic = writePem(directory.resolve("retained.pub"), "PUBLIC KEY",
                keys.rsaKey().toRSAPublicKey().getEncoded());
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var signingFile = writePem(directory.resolve("next.pem"), "PRIVATE KEY",
                generator.generateKeyPair().getPrivate().getEncoded());
        var next = new TokenProperties(properties.ttl(), properties.issuer(), "next",
                properties.audiences(), properties.clockSkew(), properties.maxSessionLifetime());
        var overlap = new JwtKeyProvider(next, new JwtKeyProperties(signingFile,
                Map.of(properties.keyId(), retainedPublic), false));
        var configuration = new TokenConfiguration();
        var overlapDecoder = configuration.jwtDecoder(overlap, next);
        assertThat(overlapDecoder.decode(beforeRotation).getSubject()).isEqualTo(user.username());
        var nextSigner = new TokenService(new NimbusJwtEncoder(new ImmutableJWKSet<>(overlap.fullJwkSet())), next);
        String afterRotation = nextSigner.issue(user).block().value();
        var retired = new JwtKeyProvider(next, new JwtKeyProperties(signingFile, Map.of(), false));
        var retiredDecoder = configuration.jwtDecoder(retired, next);
        assertThat(retiredDecoder.decode(afterRotation).getHeaders()).containsEntry("kid", "next");
        assertThatThrownBy(() -> retiredDecoder.decode(beforeRotation)).isInstanceOf(BadJwtException.class);
        // Тот же kid с чужой подписью должен отвергаться даже в период перекрытия ключей.
        var forged = sign(header(JWSAlgorithm.RS256, "at+jwt", "next"), claims());
        assertThatThrownBy(() -> overlapDecoder.decode(forged)).isInstanceOf(BadJwtException.class);
    }

    private Path writePem(Path file, String label, byte[] der) throws Exception {
        return Files.writeString(file, "-----BEGIN " + label + "-----\n" + Base64.getEncoder().encodeToString(der)
                + "\n-----END " + label + "-----\n");
    }

    @Test
    void rejectsWrongOrMissingClaimsEvenWhenSigned() throws Exception {
        rejectClaims(b -> b.issuer("https://other.example"));
        rejectClaims(b -> b.audience("other-service"));
        rejectClaims(b -> b.audience(List.of()));
        rejectClaims(b -> b.jwtID(null));
        rejectClaims(b -> b.subject(null));
        rejectClaims(b -> b.claim("username", "other-user"));
        rejectClaims(b -> b.claim("auth_source", null));
        rejectClaims(b -> b.claim("auth_time", null));
        rejectClaims(b -> b.claim("roles", "ROLE_ADMIN"));
        rejectClaims(b -> b.expirationTime(null));
        rejectClaims(b -> b.issueTime(null));
    }

    @Test
    void rejectsInvalidLifetimes() throws Exception {
        var now = Instant.now();
        rejectClaims(b -> b.notBeforeTime(Date.from(now.plusSeconds(30))));
        rejectClaims(b -> b.issueTime(Date.from(now.plusSeconds(30))));
        rejectClaims(b -> b.claim("auth_time", now.minusSeconds(7200).getEpochSecond()));
        rejectClaims(b -> b.expirationTime(Date.from(now.plusSeconds(3600))));
        rejectClaims(b -> b.claim("auth_time", now.minusSeconds(120).getEpochSecond())
                .issueTime(Date.from(now.minusSeconds(120))).expirationTime(Date.from(now.minusSeconds(60))));
    }

    @Test
    void exchangePreservesAuthenticationTimeAndCapsLifetime() throws Exception {
        long authenticated = Instant.now().minus(properties.maxSessionLifetime()).plusSeconds(90).getEpochSecond();
        var previous = decoder.decode(sign(defaultHeader(), claims().claim("auth_time", authenticated)
                .expirationTime(new Date((authenticated + properties.maxSessionLifetime().toSeconds()) * 1000))));
        var exchanged = service.reissue(user, previous).block();
        var renewed = decoder.decode(exchanged.value());
        assertThat(AccessTokenValidator.authenticationTime(renewed).getEpochSecond()).isEqualTo(authenticated);
        assertThat(exchanged.jti()).isEqualTo(renewed.getId()).isNotEqualTo(previous.getId());
        assertThat(exchanged.expiresAt()).isEqualTo(renewed.getExpiresAt());
        assertThat(renewed.getExpiresAt()).isEqualTo(Instant.ofEpochSecond(authenticated).plus(properties.maxSessionLifetime()));
        assertThat(exchanged.expiresInSeconds()).isBetween(1L, 90L);
    }

    @Test
    void expiredTokenWithinValidationSkewCannotExtendSession() throws Exception {
        var now = Instant.now();
        var prior = decoder.decode(sign(defaultHeader(), claims().issueTime(Date.from(now.minusSeconds(10)))
                .claim("auth_time", now.minusSeconds(10).getEpochSecond()).expirationTime(Date.from(now.minusSeconds(1)))));
        assertThatThrownBy(() -> service.reissue(user, prior).block()).isInstanceOf(BadJwtException.class);
    }

    private void rejectClaims(Consumer<JWTClaimsSet.Builder> change) throws Exception {
        var builder = claims();
        change.accept(builder);
        assertInvalid(defaultHeader(), builder);
    }

    private void assertInvalid(JWSHeader header, JWTClaimsSet.Builder claims) throws Exception {
        String token = sign(header, claims);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    private String sign(JWSHeader header, JWTClaimsSet.Builder claims) throws Exception {
        var jwt = new SignedJWT(header, claims.build());
        jwt.sign(new RSASSASigner(keys.rsaKey()));
        return jwt.serialize();
    }

    private JWSHeader defaultHeader() {
        return header(JWSAlgorithm.RS256, "at+jwt", properties.keyId());
    }

    private JWSHeader header(JWSAlgorithm alg, String type, String kid) {
        return new JWSHeader.Builder(alg).type(new JOSEObjectType(type)).keyID(kid).build();
    }

    private JWTClaimsSet.Builder claims() {
        var now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(properties.issuer()).audience(properties.audiences())
                .subject(user.username()).jwtID("previous-id").issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(60))).claim("username", user.username())
                .claim("roles", user.roles()).claim("auth_source", user.authSource()).claim("auth_time", now.getEpochSecond());
    }
}
