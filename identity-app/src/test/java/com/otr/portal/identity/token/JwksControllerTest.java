package com.otr.portal.identity.token;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.otr.portal.token.JwtKeyProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwksControllerTest {

    @Test
    void publishesPublicFieldsWithConfiguredCacheLifetime() throws Exception {
        var provider = mock(JwtKeyProvider.class);
        var key = new RSAKeyGenerator(2048).keyID("active").generate();
        when(provider.publicJwkSet()).thenReturn(new JWKSet(key.toPublicJWK()));
        WebTestClient.bindToController(new JwksController(provider, Duration.ofMinutes(2))).build()
                .get().uri("/.well-known/jwks.json").exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("Cache-Control", ".*max-age=120.*")
                .expectBody()
                .jsonPath("$.keys[0].kid").isEqualTo("active")
                .jsonPath("$.keys[0].n").isNotEmpty()
                .jsonPath("$.keys[0].d").doesNotExist();
    }
}
