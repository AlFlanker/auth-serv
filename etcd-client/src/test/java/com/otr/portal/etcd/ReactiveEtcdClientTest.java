package com.otr.portal.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.kv.PutResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.etcd.jetcd.watch.WatchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReactiveEtcdClientTest {

    private KV kv;
    private Client client;
    private ReactiveEtcdClient reactiveEtcdClient;

    @BeforeEach
    void setUp() {
        kv = mock(KV.class);
        client = mock(Client.class);
        when(client.getKVClient()).thenReturn(kv);
        reactiveEtcdClient = new ReactiveEtcdClient(client);
    }

    @Test
    void getShouldAdaptFutureToMono() {
        GetResponse response = mock(GetResponse.class);
        when(kv.get(any())).thenReturn(CompletableFuture.completedFuture(response));

        StepVerifier.create(reactiveEtcdClient.get(key("k")))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void putShouldAdaptFutureToMono() {
        PutResponse response = mock(PutResponse.class);
        when(kv.put(any(), any())).thenReturn(CompletableFuture.completedFuture(response));

        StepVerifier.create(reactiveEtcdClient.put(key("k"), key("v")))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void getValueAsStringShouldReturnDecodedValue() {
        KeyValue keyValue = mock(KeyValue.class);
        when(keyValue.getValue()).thenReturn(key("hello"));
        GetResponse response = mock(GetResponse.class);
        when(response.getKvs()).thenReturn(List.of(keyValue));
        when(kv.get(any())).thenReturn(CompletableFuture.completedFuture(response));

        StepVerifier.create(reactiveEtcdClient.getValueAsString("k"))
                .expectNext("hello")
                .verifyComplete();
    }

    @Test
    void getByPrefixShouldEmitEachKeyValue() {
        KeyValue first = mock(KeyValue.class);
        KeyValue second = mock(KeyValue.class);
        GetResponse response = mock(GetResponse.class);
        when(response.getKvs()).thenReturn(List.of(first, second));
        when(kv.get(any(), any(GetOption.class))).thenReturn(CompletableFuture.completedFuture(response));

        StepVerifier.create(reactiveEtcdClient.getByPrefix(key("prefix/")))
                .expectNext(first, second)
                .verifyComplete();
    }

    @Test
    void getShouldPropagateFailureAsError() {
        when(kv.get(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("etcd down")));

        StepVerifier.create(reactiveEtcdClient.get(key("k")))
                .expectErrorMessage("etcd down")
                .verify();
    }

    @Test
    void getValueAsStringShouldBeEmptyWhenKeyMissing() {
        GetResponse response = mock(GetResponse.class);
        when(response.getKvs()).thenReturn(List.of());
        when(kv.get(any())).thenReturn(CompletableFuture.completedFuture(response));

        StepVerifier.create(reactiveEtcdClient.getValueAsString("missing"))
                .verifyComplete();
    }

    @Test
    void watchShouldEmitEventsAndCloseWatcherOnTerminate() {
        Watch watchClient = mock(Watch.class);
        Watch.Watcher watcher = mock(Watch.Watcher.class);
        when(client.getWatchClient())
                .thenReturn(watchClient);

        ArgumentCaptor<Watch.Listener> listenerCaptor = ArgumentCaptor.forClass(Watch.Listener.class);
        when(watchClient.watch(any(), any(WatchOption.class), listenerCaptor.capture()))
                .thenReturn(watcher);

        KeyValue eventKeyValue = mock(KeyValue.class);
        when(eventKeyValue.getKey()).thenReturn(key("k"));
        WatchEvent event = mock(WatchEvent.class);
        when(event.getKeyValue()).thenReturn(eventKeyValue);
        WatchResponse response = mock(WatchResponse.class);
        when(response.getEvents()).thenReturn(List.of(event));

        StepVerifier.create(reactiveEtcdClient.watch(key("k")))
                .then(() -> {
                    Watch.Listener listener = listenerCaptor.getValue();
                    listener.onNext(response);
                    listener.onCompleted();
                })
                .expectNext(event)
                .verifyComplete();

        verify(watcher).close();
    }

    @Test
    void watchShouldCloseWatcherOnCancel() {
        Watch watchClient = mock(Watch.class);
        Watch.Watcher watcher = mock(Watch.Watcher.class);
        when(client.getWatchClient()).thenReturn(watchClient);
        when(watchClient.watch(any(), any(WatchOption.class), any(Watch.Listener.class)))
                .thenReturn(watcher);

        StepVerifier.create(reactiveEtcdClient.watch(key("k")))
                .thenCancel()
                .verify();

        verify(watcher).close();
    }

    @Test
    void watchShouldPropagateListenerErrors() {
        Watch watchClient = mock(Watch.class);
        Watch.Watcher watcher = mock(Watch.Watcher.class);
        when(client.getWatchClient()).thenReturn(watchClient);

        ArgumentCaptor<Watch.Listener> listenerCaptor = ArgumentCaptor.forClass(Watch.Listener.class);
        when(watchClient.watch(any(), any(WatchOption.class), listenerCaptor.capture()))
                .thenReturn(watcher);

        StepVerifier.create(reactiveEtcdClient.watch(key("k")))
                .then(() -> listenerCaptor.getValue().onError(new IllegalStateException("watch broken")))
                .expectErrorMessage("watch broken")
                .verify();

        verify(watcher).close();
    }

    private static ByteSequence key(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }
}
