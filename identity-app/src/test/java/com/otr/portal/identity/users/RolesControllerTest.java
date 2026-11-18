package com.otr.portal.identity.users;

import com.nimbusds.jose.jwk.JWKSet;
import com.otr.portal.identity.token.JwksController;
import com.otr.portal.token.JwtKeyProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.LoopResources;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RolesControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebFlux
    static class WebConfig {}

    @Test
    void slowRoleQueryLeavesTheNettyEventLoopAvailableForJwks() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var roles = mock(RoleService.class);
        when(roles.getRoles()).thenAnswer(call -> {
            started.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of();
        });
        when(roles.getRole("missing")).thenAnswer(call -> {
            assertThat(Schedulers.isInNonBlockingThread()).isFalse();
            return Optional.empty();
        });
        var provider = mock(JwtKeyProvider.class);
        when(provider.publicJwkSet()).thenReturn(new JWKSet());

        var loop = LoopResources.create("roles-test", 1, true);
        try (var context = new AnnotationConfigApplicationContext(); var client = HttpClient.newHttpClient()) {
            context.register(WebConfig.class);
            context.registerBean(RolesController.class, () -> new RolesController(roles));
            context.registerBean(JwksController.class, () -> new JwksController(provider, Duration.ZERO));
            context.refresh();
            var handler = new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(context).build());
            var server = HttpServer.create().host("127.0.0.1").port(0).runOn(loop).handle(handler).bindNow();
            try {
                var root = URI.create("http://127.0.0.1:" + server.port());
                var body = HttpResponse.BodyHandlers.discarding();
                var listing = client.sendAsync(request(root, "/roles"), body);
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(client.send(request(root, "/.well-known/jwks.json"), body).statusCode()).isEqualTo(200);
                release.countDown();
                assertThat(listing.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                assertThat(client.send(request(root, "/roles/missing"), body).statusCode()).isEqualTo(404);
            } finally {
                release.countDown();
                server.disposeNow();
            }
        } finally {
            loop.disposeLater().block(Duration.ofSeconds(10));
        }
    }

    private HttpRequest request(URI root, String path) {
        return HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(5)).build();
    }
}
