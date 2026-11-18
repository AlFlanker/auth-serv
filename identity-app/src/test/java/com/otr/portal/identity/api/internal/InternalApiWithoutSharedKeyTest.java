package com.otr.portal.identity.api.internal;

import com.otr.portal.identity.token.SsoPrincipalExtractor;
import com.otr.portal.identity.token.TokenController.TokenView;
import com.otr.portal.oss.model.Profile;
import com.otr.portal.oss.userdetails.ProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "identity.internal.require-api-key=false")
@ActiveProfiles("test")
class InternalApiWithoutSharedKeyTest {
    @LocalServerPort
    private int port;

    @MockitoBean
    private ProfileService profiles;

    @MockitoBean
    private SsoPrincipalExtractor sso;

    @Test
    void groupsAreAccessibleAndExchangeStillRequiresAnActiveBearer() {
        var client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        var roles = List.of("ROLE_USER");
        when(profiles.getUserAuthorities("ivanov")).thenReturn(Mono.just(roles));
        client.post().uri("/api/internal/v1/users/ivanov/groups").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0]").isEqualTo("ROLE_USER");

        String exchange = "/api/internal/token/exchange";
        client.post().uri(exchange).exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");

        when(sso.extract(any())).thenReturn(Mono.just(new SsoPrincipalExtractor.SsoPrincipal("ivanov", "X-User")));
        when(profiles.loadWithoutCache("ivanov")).thenReturn(Mono.just(new Profile("ivanov", "Иванов",
                null, null, null, null, null, null, null, null, null, null, roles, List.of(), null, null)));
        var issued = client.post().uri("/api/v1/token").exchange().expectStatus().isOk()
                .expectBody(TokenView.class).returnResult().getResponseBody();
        client.post().uri(exchange).headers(headers -> headers.setBearerAuth(issued.accessToken()))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.accessToken").isNotEmpty();
    }
}
