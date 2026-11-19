package com.otr.portal.identity.api.profile;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.otr.portal.token.JwtKeyProperties;
import com.otr.portal.token.JwtKeyProvider;
import com.otr.portal.token.TokenClaims;
import com.otr.portal.token.TokenConfiguration;
import com.otr.portal.token.TokenProperties;
import com.otr.portal.token.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты {@link JwtAuthenticationWebFilter}: защищённые пути, 401 без/с невалидным
 * Bearer, прозрачный пропуск незащищённых путей.
 */
class JwtAuthenticationWebFilterTest {

    private final TokenProperties properties =
            new TokenProperties(Duration.ofMinutes(30), "https://identity-service.test", "test-kid", null, null, null);

    private TokenService tokenService;
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() throws Exception {
        var keyProvider = new JwtKeyProvider(properties, new JwtKeyProperties(null, Map.of(), true));
        tokenService = new TokenService(new NimbusJwtEncoder(new ImmutableJWKSet<>(keyProvider.fullJwkSet())), properties);
        jwtDecoder = new TokenConfiguration().jwtDecoder(keyProvider, properties);
    }

    @Test
    void requestWithoutAuthorization_returns401() {
        var exchange = exchange(MockServerHttpRequest.get("/currentuser").build());
        filter(exchange);
        assertUnauthorized(exchange);
    }

    @Test
    void requestWithMalformedBearer_returns401() {
        var exchange = exchange(MockServerHttpRequest.get("/currentuser")
                .header(HttpHeaders.AUTHORIZATION, "Bearer")
                .build());
        filter(exchange);
        assertUnauthorized(exchange);
    }

    @Test
    void requestWithGarbageToken_returns401() {
        var exchange = exchange(MockServerHttpRequest.get("/currentuser")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .build());
        filter(exchange);
        assertUnauthorized(exchange);
    }

    @Test
    void requestWithValidToken_storesAuthenticatedUserFromClaims() {
        String token = tokenService.mint(new TokenClaims("ivanov", List.of("ROLE_USER", "ROLE_EDITOR"),
                "GUID-1", "ORG1", "X-Auth")).block();
        var exchange = exchange(MockServerHttpRequest.get("/user/current/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build());

        AtomicReference<AuthenticatedUser> captured = new AtomicReference<>();
        new JwtAuthenticationWebFilter(jwtDecoder).filter(exchange, chain -> {
            captured.set(exchange.getAttribute(CurrentUserResolver.USER_ATTRIBUTE));
            return Mono.empty();
        }).block();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().login()).isEqualTo("ivanov");
        assertThat(captured.get().roles()).containsExactly("ROLE_USER", "ROLE_EDITOR");
        assertThat(captured.get().orgCode()).isEqualTo("ORG1");
        assertThat(captured.get().orgGuid()).isEqualTo("GUID-1");
        assertThat(captured.get().authSource()).isEqualTo("X-Auth");
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void unprotectedPath_passesThroughWithoutUser() {
        var exchange = exchange(MockServerHttpRequest.get("/api/v1/token").build());
        AtomicReference<Boolean> reached = new AtomicReference<>(false);

        new JwtAuthenticationWebFilter(jwtDecoder).filter(exchange, chain -> {
            reached.set(true);
            return Mono.empty();
        }).block();

        assertThat(reached.get()).isTrue();
        Object user = exchange.getAttribute(CurrentUserResolver.USER_ATTRIBUTE);
        assertThat(user).isNull();
    }

    private void filter(MockServerWebExchange exchange) {
        new JwtAuthenticationWebFilter(jwtDecoder).filter(exchange, ignored -> {
            throw new AssertionError("Защищённый путь должен быть отклонён до обработчика");
        }).block();
    }

    private MockServerWebExchange exchange(MockServerHttpRequest request) {
        return MockServerWebExchange.from(request);
    }

    private void assertUnauthorized(MockServerWebExchange exchange) {
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }
}
