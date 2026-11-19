package com.otr.portal.identity.api.profile;

import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.model.Profile;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты {@link ActiveOrganizationResolver}: приоритет источников — claim JWT,
 * cookie {@code ACTIVE_ORG}, единственная организация; иначе 428-исключение.
 */
class ActiveOrganizationResolverTest {

    private final ActiveOrganizationResolver resolver = new ActiveOrganizationResolver();

    private static Profile profile(String... orgCodes) {
        List<Organization> organizations = List.of(orgCodes).stream()
                .map(code -> new Organization(code, code, code, "GUID-" + code,
                        null, null, null, null, (short) 1))
                .toList();
        return new Profile("ivanov", "Иванов Иван", null, null, null, null, null,
                null, "ivanov", null, null, null,
                List.of("ROLE_USER"), organizations, null, null, false);
    }

    @Test
    void claimOrgCode_winsOverCookie() {
        var user = new AuthenticatedUser("ivanov", "ORG1", "GUID-ORG1", "X-Auth");
        var exchange = exchangeWithCookie("ORG2");

        StepVerifier.create(resolver.resolve(profile("ORG1", "ORG2"), user, exchange))
                .assertNext(org -> assertThat(org.orgSvrCode()).isEqualTo("ORG1"))
                .verifyComplete();
    }

    @Test
    void cookie_selectsOrganization_whenClaimAbsent() {
        var user = new AuthenticatedUser("ivanov", null, null, "X-Auth");
        var exchange = exchangeWithCookie("ORG2");

        StepVerifier.create(resolver.resolve(profile("ORG1", "ORG2"), user, exchange))
                .assertNext(org -> assertThat(org.orgSvrCode()).isEqualTo("ORG2"))
                .verifyComplete();
    }

    @Test
    void cookieValue_isUrlDecoded() {
        var user = new AuthenticatedUser("ivanov", null, null, "X-Auth");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/user/current/profile")
                .cookie(new HttpCookie(ActiveOrganizationResolver.ACTIVE_ORG_COOKIE,
                        URLEncoder.encode("ORG 2", StandardCharsets.UTF_8)))
                .build());

        StepVerifier.create(resolver.resolve(profile("ORG1", "ORG 2"), user, exchange))
                .assertNext(org -> assertThat(org.orgSvrCode()).isEqualTo("ORG 2"))
                .verifyComplete();
    }

    @Test
    void staleClaimOrCookie_fallsThroughToSingleOrganization() {
        var user = new AuthenticatedUser("ivanov", "STALE", null, "X-Auth");
        var exchange = exchangeWithCookie("STALE-TOO");

        StepVerifier.create(resolver.resolve(profile("ORG1"), user, exchange))
                .assertNext(org -> assertThat(org.orgSvrCode()).isEqualTo("ORG1"))
                .verifyComplete();
    }

    @Test
    void multiOrgWithoutAnySource_throwsOrgNotSelected() {
        var user = new AuthenticatedUser("ivanov", null, null, "X-Auth");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/user/current/profile").build());

        StepVerifier.create(resolver.resolve(profile("ORG1", "ORG2"), user, exchange))
                .expectErrorMatches(error -> error instanceof OrgNotSelectedException e
                        && e.getMessage().contains("Иванов Иван"))
                .verify();
    }

    private MockServerWebExchange exchangeWithCookie(String code) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/user/current/profile")
                .cookie(new HttpCookie(ActiveOrganizationResolver.ACTIVE_ORG_COOKIE,
                        URLEncoder.encode(code, StandardCharsets.UTF_8)))
                .build());
    }
}
