package com.otr.portal.identity.token;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.otr.portal.config.source.ConfigKey;
import com.otr.portal.config.source.ConfigSource;
import com.otr.portal.identity.users.LocalUserService;
import com.otr.portal.oss.config.OssConfig;
import com.otr.portal.oss.exception.ProfileNotFoundException;
import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.model.Profile;
import com.otr.portal.oss.userdetails.ProfileService;
import com.otr.portal.token.JwtKeyProvider;
import com.otr.portal.token.JwtKeyProperties;
import com.otr.portal.token.TokenClaims;
import com.otr.portal.token.TokenConfiguration;
import com.otr.portal.token.TokenProperties;
import com.otr.portal.token.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * Тесты TokenController: выпуск по SSO-заголовку и паролю, exchange, logout.
 */
class TokenControllerTest {

    private final ProfileService profileService = Mockito.mock(ProfileService.class);
    private final LocalUserService localUserService = Mockito.mock(LocalUserService.class);
    private final TokenRegistry tokenRegistry = Mockito.mock(TokenRegistry.class);
    private final TokenProperties properties = new TokenProperties(Duration.ofMinutes(30), "https://identity-service.test", "test-kid", null, null, null);

    private TokenService tokenService;
    private JwtDecoder jwtDecoder;
    private WebTestClient client;

    @BeforeEach
    void setUp() throws Exception {
        configure(new AuthenticationProperties(true, false));
    }

    private void configure(AuthenticationProperties authentication) throws Exception {
        var keyProvider = new JwtKeyProvider(properties, new JwtKeyProperties(null, Map.of(), true));
        tokenService = new TokenService(new NimbusJwtEncoder(new ImmutableJWKSet<>(keyProvider.fullJwkSet())), properties);
        jwtDecoder = new TokenConfiguration().jwtDecoder(keyProvider, properties);

        var configSource = Mockito.mock(ConfigSource.class);
        var ossConfigKey = ConfigKey.of("OSSConfig", OssConfig.class, raw -> null);
        when(configSource.get(ossConfigKey)).thenReturn(Mono.just(new OssConfig(
                OssConfig.OssType.SOBI_WEB_SERVICE, "http://oss/wsdl", "key", 60,
                "X-Auth", true, 1, 20, 1, 20, 5, 20, "User-Selected-OrgCode")));
        var extractor = new SsoPrincipalExtractor(configSource, ossConfigKey, authentication);

        var controller = new TokenController(
                extractor, profileService, localUserService, tokenService,
                tokenRegistry, jwtDecoder, authentication);
        client = WebTestClient.bindToController(controller).build();
        when(tokenRegistry.register(any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void token_bySsoHeader_returnsSignedTokenWithProfileClaims() {
        when(profileService.loadWithoutCache("ivanov")).thenReturn(Mono.just(new Profile(
                "ivanov", "Иванов Иван", null, null, null, null, null, null,
                "ivanov", null, null, null,
                List.of("ROLE_ADMIN"),
                List.of(new Organization("Организация", "Организация", "ORG-1", "GUID-1",
                        null, null, null, null, null)),
                "ORG-1", "GUID-1", false)));

        var token = client.post().uri("/api/v1/token")
                .header("X-Auth", "ivanov")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody(TokenController.TokenView.class)
                .consumeWith(response ->
                        org.assertj.core.api.Assertions.assertThat(response.getResponseBody()).isNotNull())
                .returnResult()
                .getResponseBody();

        assertThat(token).isNotNull();
        var jwt = jwtDecoder.decode(token.accessToken());
        assertThat(jwt.getSubject()).isEqualTo("ivanov");
        Mockito.verify(tokenRegistry).register(jwt.getId(), jwt.getExpiresAt());
        assertThat(jwt.getClaimAsString("auth_source")).isEqualTo("X-Auth");
        assertThat(jwt.getClaimAsString("org_code")).isEqualTo("ORG-1");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_ADMIN");
    }

    @Test
    void token_byLocalPassword_returnsTokenWithLocalRoles() {
        when(localUserService.verify("user", "123")).thenReturn(List.of("ROLE_ADMIN", "ROLE_USER"));

        var token = client.post().uri("/api/v1/token")
                .contentType(APPLICATION_JSON)
                .bodyValue("{\"username\":\"user\",\"password\":\"123\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody(TokenController.TokenView.class)
                .returnResult()
                .getResponseBody();

        assertThat(token).isNotNull();
        var jwt = jwtDecoder.decode(token.accessToken());
        assertThat(jwt.getClaimAsString("auth_source")).isEqualTo("password");
        assertThat(jwt.getClaimAsString("org_code")).isNull();
    }

    @Test
    void registrationFailureDoesNotExposeTheSignedToken() {
        when(localUserService.verify("user", "valid")).thenReturn(List.of("ROLE_USER"));
        when(tokenRegistry.register(any(), any())).thenReturn(Mono.error(new IllegalStateException("DB unavailable")));
        var response = client.post().uri("/api/v1/token").contentType(APPLICATION_JSON)
                .bodyValue(new TokenController.TokenRequest("user", "valid"))
                .exchange().expectStatus().is5xxServerError().expectBody(String.class).returnResult();
        assertThat(java.util.Objects.toString(response.getResponseBody(), "")).doesNotContain("accessToken", "eyJ");
    }

    @Test
    void token_byWrongPassword_returns401() {
        when(localUserService.verify("user", "wrong")).thenReturn(null);

        client.post().uri("/api/v1/token")
                .contentType(APPLICATION_JSON)
                .bodyValue("{\"username\":\"user\",\"password\":\"wrong\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void exchange_reissuesTokenWithFreshRoles() {
        var original = tokenService.mint(new TokenClaims(
                "ivanov", List.of("ROLE_OLD"), "GUID-1", "ORG-1", "X-Auth")).block();
        when(tokenRegistry.isActive(any())).thenReturn(Mono.just(true));
        when(profileService.loadWithoutCache("ivanov")).thenReturn(Mono.just(new Profile(
                "ivanov", null, null, null, null, null, null, null, "ivanov", null, null, null,
                List.of("ROLE_FRESH"), List.of(), "ORG-NEW", "GUID-NEW", false)));
        when(tokenRegistry.exchange(any(), any(), any())).thenReturn(Mono.just(true));

        var body = client.post().uri("/api/internal/token/exchange")
                .header("Authorization", "Bearer " + original)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody(TokenController.TokenView.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        var jwt = jwtDecoder.decode(body.accessToken());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_FRESH");
        assertThat(jwt.getClaimAsString("org_code")).isEqualTo("ORG-NEW");
    }

    @Test
    void exchangeReportsDatabaseTimeoutAsServerError() {
        var original = tokenService.mint(new TokenClaims("user", List.of(), null, null, "password")).block();
        when(tokenRegistry.isActive(any())).thenReturn(Mono.just(true));
        when(localUserService.authorities("user")).thenReturn(List.of("ROLE_USER"));
        when(tokenRegistry.exchange(any(), any(), any())).thenReturn(Mono.error(new QueryTimeoutException("timeout")));

        client.post().uri("/api/internal/token/exchange").header("Authorization", "Bearer " + original)
                .exchange().expectStatus().is5xxServerError()
                .expectHeader().doesNotExist("WWW-Authenticate");
    }

    @Test
    void exchange_revokedToken_returns401() {
        var original = tokenService.mint(new TokenClaims(
                "ivanov", List.of("ROLE_OLD"), null, null, "X-Auth")).block();
        when(tokenRegistry.isActive(any())).thenReturn(Mono.just(false));

        client.post().uri("/api/internal/token/exchange")
                .header("Authorization", "Bearer " + original)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void logout_revokesToken() {
        var original = tokenService.mint(new TokenClaims(
                "ivanov", List.of(), null, null, "X-Auth")).block();
        when(tokenRegistry.revoke(any())).thenReturn(Mono.empty());

        client.post().uri("/api/v1/logout")
                .header("Authorization", "Bearer " + original)
                .exchange()
                .expectStatus().isNoContent();
        client.post().uri("/api/v1/logout")
                .header("Authorization", "Bearer " + original)
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void headerOnlyModeRejectsCookiesPasswordsAndDuplicateHeaders() throws Exception {
        configure(new AuthenticationProperties(false, false));
        client.post().uri("/api/v1/token").cookie("X-Auth", "admin").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/token").header("X-Auth", "user", "admin").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/token").contentType(APPLICATION_JSON)
                .bodyValue(new TokenController.TokenRequest("admin", "password")).exchange().expectStatus().isUnauthorized();
        Mockito.verifyNoInteractions(profileService, localUserService);
    }

    @Test
    void malformedBearerIsRejectedBeforeRegistryAccess() {
        client.post().uri("/api/internal/token/exchange").header("Authorization", "Bearer invalid")
                .exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer");
        client.post().uri("/api/v1/logout").header("Authorization", "Bearer first", "Bearer second")
                .exchange().expectStatus().isUnauthorized();
        Mockito.verifyNoInteractions(profileService, tokenRegistry);
    }

    @Test
    void exchangeLosesRaceToLogout() {
        var original = tokenService.mint(new TokenClaims("user", List.of(), null, null, "password")).block();
        when(tokenRegistry.isActive(any())).thenReturn(Mono.just(true));
        when(localUserService.authorities("user")).thenReturn(List.of("ROLE_USER"));
        when(tokenRegistry.exchange(any(), any(), any())).thenReturn(Mono.just(false));
        client.post().uri("/api/internal/token/exchange").header("Authorization", "Bearer " + original)
                .exchange().expectStatus().isUnauthorized();
        Mockito.verifyNoInteractions(profileService);
    }

    @Test
    void missingSsoProfilePreventsIssuanceAndExchange() {
        var original = tokenService.mint(new TokenClaims("admin", List.of(), null, null, "X-Auth")).block();
        when(tokenRegistry.isActive(any())).thenReturn(Mono.just(true));
        when(profileService.loadWithoutCache("admin")).thenReturn(Mono.error(new ProfileNotFoundException("admin")));
        client.post().uri("/api/v1/token").header("X-Auth", "admin")
                .exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/internal/token/exchange").header("Authorization", "Bearer " + original)
                .exchange().expectStatus().isUnauthorized();
        Mockito.verifyNoInteractions(localUserService);
        Mockito.verify(tokenRegistry, Mockito.never()).exchange(any(), any(), any());
        Mockito.verify(tokenRegistry, Mockito.never()).register(any(), any());
    }
}
