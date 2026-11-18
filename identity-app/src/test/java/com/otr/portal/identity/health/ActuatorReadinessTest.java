package com.otr.portal.identity.health;

import com.otr.portal.config.source.WatchableConfigSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMetrics(export = true)
@Import(ActuatorReadinessTest.ConfigFixture.class)
class ActuatorReadinessTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ConfigFixture {
        @Bean
        WatchableConfigSource probeTestSource() {
            var source = mock(WatchableConfigSource.class, CALLS_REAL_METHODS);
            doReturn(Mono.empty()).when(source).get(anyString());
            doReturn(Flux.never()).when(source).watch(anyString());
            return source;
        }
    }

    @Autowired
    private WatchableConfigSource source;

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void connect() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void readinessFollowsConfigurationAndLivenessRemainsIndependent() {
        doReturn(Mono.empty()).when(source).get("OSSConfig");
        assertReadiness(503);
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();

        doReturn(Mono.just("""
                {"ossType":"USB","headerName":"X-User","ossUrl":"https://usb.example"}
                """)).when(source).get("OSSConfig");
        assertReadiness(503);
        doReturn(Mono.just("{}")).when(source).get("UsbConfig");
        assertReadiness(200);

        doReturn(Mono.error(new IllegalStateException("unavailable"))).when(source).get("UsbConfig");
        assertReadiness(503);
        doReturn(Mono.just("""
                {"ossType":"SOBI_WEB_SERVICE","headerName":"X-User","ossUrl":"https://oss.example/profile?wsdl"}
                """)).when(source).get("OSSConfig");
        assertReadiness(200);

        doReturn(Mono.just("not valid JSON")).when(source).get("OSSConfig");
        assertReadiness(503);
        doReturn(Mono.just("""
                {"headerName":"password","ossUrl":"https://oss.example"}
                """)).when(source).get("OSSConfig");
        assertReadiness(503);
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
    }

    @Test
    void prometheusCanScrapeApplicationMetrics() {
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains("jvm_memory_used_bytes"));
    }

    private void assertReadiness(int status) {
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isEqualTo(status)
                .expectBody().jsonPath("$.status").isEqualTo(status == 200 ? "UP" : "DOWN");
    }
}
