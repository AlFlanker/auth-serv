package com.otr.portal.token;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты выпуска токенов: полный набор claims и корректная подпись (RS256, JWKS-ready).
 */
class TokenServiceTest {

    private TokenService tokenService;
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() throws Exception {
        var properties = new TokenProperties(Duration.ofMinutes(30), "https://identity-service.test", "test-kid");
        var keyProvider = new JwtKeyProvider(properties);
        var jwkSource = new ImmutableJWKSet<>(keyProvider.fullJwkSet());
        tokenService = new TokenService(new NimbusJwtEncoder(jwkSource), properties);
        jwtDecoder = NimbusJwtDecoder.withPublicKey(keyProvider.rsaKey().toRSAPublicKey()).build();
    }

    @Test
    void mint_producesTokenWithAllClaimsAndValidSignature() {
        var token = tokenService.mint(new TokenClaims(
                "ivanov",
                List.of("ROLE_ADMIN", "ROLE_USER"),
                "GUID-1",
                "ORG-1",
                "X-Auth")).block();

        assertThat(token).isNotBlank();
        var jwt = jwtDecoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo("ivanov");
        assertThat(jwt.getClaimAsString("username")).isEqualTo("ivanov");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_ADMIN", "ROLE_USER");
        assertThat(jwt.getClaimAsString("org_guid")).isEqualTo("GUID-1");
        assertThat(jwt.getClaimAsString("org_code")).isEqualTo("ORG-1");
        assertThat(jwt.getClaimAsString("auth_source")).isEqualTo("X-Auth");
        assertThat(jwt.getIssuer().toString()).isEqualTo("https://identity-service.test");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getExpiresAt()).isNotNull();
        assertThat(jwt.getHeaders().get("kid")).isEqualTo("test-kid");
    }

    @Test
    void mint_withoutOrganization_omitsOrgClaims() {
        var token = tokenService.mint(new TokenClaims(
                "admin", List.of("ROLE_ADMIN"), null, null, "password")).block();

        var jwt = jwtDecoder.decode(token);

        assertThat(jwt.getClaimAsString("org_guid")).isNull();
        assertThat(jwt.getClaimAsString("org_code")).isNull();
        assertThat(jwt.getClaimAsString("auth_source")).isEqualTo("password");
    }
}
