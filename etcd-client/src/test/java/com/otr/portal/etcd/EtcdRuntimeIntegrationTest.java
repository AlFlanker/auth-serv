package com.otr.portal.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.cluster.Member;
import io.etcd.jetcd.watch.WatchEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Для отладки!!
 * <p>
 * Тест пропускается, если ETCD недоступен, поэтому не ломает обычную сборку/CI.
 *
 * @author alexraspopin
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "etcd.endpoints=http://localhost:2379",
                "spring.main.web-application-type=reactive"
        }
)
class EtcdRuntimeIntegrationTest {

    private static final String ETCD_HOST = "localhost";
    private static final int ETCD_PORT = 2379;

    @Autowired
    private ReactiveEtcdClient reactiveEtcdClient;

    @Autowired
    private Client etcdClient;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeAll
    static void requireEtcd() {
        assumeTrue(isReachable(), () ->
                "ETCD недоступен на " + ETCD_HOST + ":" + ETCD_PORT + " — интеграционный тест пропущен");
    }

    @Test
    void shouldStartReactiveWebServerAlongsideGrpcNettyClient() {
        assertThat(applicationContext).isInstanceOf(WebServerApplicationContext.class);
        int port = ((WebServerApplicationContext) applicationContext).getWebServer().getPort();
        assertThat(port).isGreaterThan(0);
    }

    @Test
    void shouldConnectToRunningCluster() {
        List<String> members = Mono.fromFuture(() -> etcdClient.getClusterClient().listMember())
                .map(response -> response.getMembers().stream().map(Member::getName).toList())
                .block(Duration.ofSeconds(5));

        assertThat(members).isNotNull();
    }

    @Test
    void shouldRoundTripPutGetDeleteReactively() {
        ByteSequence key = bytes("/nav-composer/it/" + UUID.randomUUID());
        ByteSequence value = bytes("hello-etcd");

        StepVerifier.create(
                        reactiveEtcdClient.put(key, value)
                                .then(reactiveEtcdClient.getValueAsString(key.toString(StandardCharsets.UTF_8)))
                )
                .expectNext("hello-etcd")
                .verifyComplete();

        StepVerifier.create(reactiveEtcdClient.delete(key))
                .assertNext(response -> assertThat(response.getDeleted()).isEqualTo(1L))
                .verifyComplete();

        StepVerifier.create(reactiveEtcdClient.getValueAsString(key.toString(StandardCharsets.UTF_8)))
                .verifyComplete();
    }

    @Test
    void shouldDeliverWatchEventOnPut() {
        String prefix = "/nav-composer/it-watch/";
        ByteSequence key = bytes(prefix + UUID.randomUUID());
        try {
            StepVerifier.create(reactiveEtcdClient.watchByPrefix(bytes(prefix)).take(1))
                    .then(() -> {
                        // даём watch-стриму установиться, затем пишем ключ
                        sleep(Duration.ofMillis(500));
                        reactiveEtcdClient.put(key, bytes("watched")).block(Duration.ofSeconds(5));
                    })
                    .assertNext(event -> assertThat(event.getEventType()).isEqualTo(WatchEvent.EventType.PUT))
                    .expectComplete()
                    .verify(Duration.ofSeconds(10));
        } finally {
            reactiveEtcdClient.delete(key).block(Duration.ofSeconds(5));
        }
    }

    @Test
    void shouldLoadListOfExistingKey() {
        StepVerifier.create(reactiveEtcdClient.get(bytes("fk-portal/HostsConfiguration")))
                .assertNext(event ->
                        System.out.println(event.getKvs()))
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ByteSequence bytes(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }

    private static boolean isReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ETCD_HOST, ETCD_PORT), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
