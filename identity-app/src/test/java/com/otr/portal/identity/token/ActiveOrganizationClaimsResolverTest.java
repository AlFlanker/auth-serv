package com.otr.portal.identity.token;

import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.model.Profile;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты {@link ActiveOrganizationClaimsResolver}: приоритет prior JWT → cookie
 * {@code ACTIVE_ORG} → автовыбор единственной организации; валидация по списку
 * организаций профиля.
 */
class ActiveOrganizationClaimsResolverTest {

    private final ActiveOrganizationClaimsResolver resolver = new ActiveOrganizationClaimsResolver();

    private static Profile profile(String... orgCodes) {
        List<Organization> organizations = List.of(orgCodes).stream()
                .map(code -> new Organization(code, code, code, "GUID-" + code,
                        null, null, null, null, (short) 1))
                .toList();
        return new Profile("ivanov", null, null, null, null, null, null, null,
                "ivanov", null, null, null,
                List.of(), organizations, null, null, false);
    }

    private static Jwt jwt(String orgCode) {
        Map<String, Object> claims = new HashMap<>();
        if (orgCode != null) {
            claims.put("org_code", orgCode);
        }
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private static MockServerWebExchange exchangeWithCookie(String code) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/token")
                .cookie(new HttpCookie("ACTIVE_ORG", URLEncoder.encode(code, StandardCharsets.UTF_8)))
                .build());
    }

    @Test
    void priorTokenOrganization_winsOverCookie() {
        var resolved = resolver.resolve(profile("ORG1", "ORG2"), jwt("ORG1"), exchangeWithCookie("ORG2"));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().orgSvrCode()).isEqualTo("ORG1");
    }

    @Test
    void stalePriorTokenOrganization_fallsBackToCookie() {
        var resolved = resolver.resolve(profile("ORG1", "ORG2"), jwt("ORG-STALE"), exchangeWithCookie("ORG2"));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().orgSvrCode()).isEqualTo("ORG2");
    }

    @Test
    void cookieOrganization_usedForIssuanceWithoutPriorToken() {
        var resolved = resolver.resolve(profile("ORG1", "ORG2"), null, exchangeWithCookie("ORG2"));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().orgSvrCode()).isEqualTo("ORG2");
        assertThat(resolved.get().orgGUID()).isEqualTo("GUID-ORG2");
    }

    @Test
    void multiOrgWithoutSources_returnsEmpty() {
        var resolved = resolver.resolve(profile("ORG1", "ORG2"), null,
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/token").build()));

        assertThat(resolved).isEmpty();
    }

    @Test
    void singleOrganization_isAutoselected() {
        var resolved = resolver.resolve(profile("ORG1"), null,
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/token").build()));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().orgSvrCode()).isEqualTo("ORG1");
    }

    @Test
    void staleCookieOrganization_fallsThroughToAutoselect() {
        var resolved = resolver.resolve(profile("ORG1"), null, exchangeWithCookie("ORG-STALE"));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().orgSvrCode()).isEqualTo("ORG1");
    }
}
