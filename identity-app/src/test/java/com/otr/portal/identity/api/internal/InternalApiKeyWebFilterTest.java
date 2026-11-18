package com.otr.portal.identity.api.internal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiKeyWebFilterTest {

    @Test
    void exchangeRequiresExplicitSingleInternalCredential() {
        assertDenied("", MockServerHttpRequest.post("/api/internal/token/exchange").build());
        assertDenied("configured-key", MockServerHttpRequest.post("/api/internal/token/exchange")
                .header("X-Internal-Key", "configured-key", "another-value").build());
        assertDenied("configured-key", MockServerHttpRequest.post("/api;v=1/internal/token/exchange").build());
    }

    private void assertDenied(String configuredKey, MockServerHttpRequest request) {
        var exchange = MockServerWebExchange.from(request);
        new InternalApiKeyWebFilter(configuredKey).filter(exchange, ignored -> {
            throw new AssertionError("Internal request reached the handler");
        }).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
