package com.otr.portal.identity.api.internal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiKeyWebFilterTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(InternalApiKeyWebFilter.class);

    @Test
    void internalApiIsClosedByDefaultWhenNoKeyIsConfigured() {
        context.run(app -> assertDenied(app.getBean(InternalApiKeyWebFilter.class),
                MockServerHttpRequest.post("/api/internal/token/exchange").build()));
    }

    @Test
    void exchangeRequiresExplicitSingleInternalCredential() {
        context.withPropertyValues("identity.internal.api-key=configured-key").run(app -> {
            var filter = app.getBean(InternalApiKeyWebFilter.class);
            assertDenied(filter, MockServerHttpRequest.post("/api/internal/token/exchange")
                    .header("X-Internal-Key", "configured-key", "another-value").build());
            assertDenied(filter, MockServerHttpRequest.post("/api;v=1/internal/token/exchange").build());
        });
    }

    private void assertDenied(InternalApiKeyWebFilter filter, MockServerHttpRequest request) {
        var exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, ignored -> {
            throw new AssertionError("Internal request reached the handler");
        }).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
