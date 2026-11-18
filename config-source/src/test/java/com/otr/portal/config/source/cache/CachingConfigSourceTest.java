package com.otr.portal.config.source.cache;

import com.otr.portal.config.source.ConfigChange;
import com.otr.portal.config.source.ConfigChangeType;
import com.otr.portal.config.source.ConfigKey;
import com.otr.portal.config.source.WatchableConfigSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CachingConfigSourceTest {

    private final WatchableConfigSource delegate = mock(WatchableConfigSource.class);
    private final ConfigKey<Integer> key = ConfigKey.of("size", Integer.class, Integer::valueOf);
    private final ConfigChange sync = new ConfigChange(ConfigChangeType.SYNC, "", null);

    @Test
    void reconnectRefreshesDerivedStateEvenForChangesWithoutSubsequentPut() {
        var raw = new AtomicReference<>("1");
        var first = Sinks.many().multicast().<ConfigChange>onBackpressureBuffer();
        var second = Sinks.many().multicast().<ConfigChange>onBackpressureBuffer();
        var subscriptions = new AtomicInteger();
        var third = Sinks.many().multicast().<ConfigChange>onBackpressureBuffer();
        when(delegate.get(key.key())).thenAnswer(invocation -> Mono.justOrEmpty(raw.get()));
        when(delegate.watch("")).thenReturn(Flux.defer(() -> {
            var events = switch (subscriptions.getAndIncrement()) {
                case 0 -> first;
                case 1 -> second;
                default -> third;
            };
            return Flux.concat(Mono.just(sync), events.asFlux());
        }));
        var properties = new ConfigCacheProperties();
        properties.setWatchRetryBackoff(Duration.ofSeconds(1));
        properties.setWatchRetryMaxBackoff(Duration.ofSeconds(1));
        var source = new CachingConfigSource(delegate, properties, List.of(key));
        try {
            StepVerifier.withVirtualTime(() -> source.watchKeyIncludingSync(key.key())
                            .concatMap(change -> source.get(key).defaultIfEmpty(-1)))
                    .expectNext(1)
                    .then(() -> {
                        raw.set("2");
                        first.tryEmitComplete().orThrow();
                    })
                    .thenAwait(Duration.ofSeconds(2))
                    .expectNext(2)
                    .then(() -> {
                        raw.set(null);
                        second.tryEmitError(new IllegalStateException()).orThrow();
                    })
                    .thenAwait(Duration.ofSeconds(2))
                    .expectNext(-1)
                    .thenCancel()
                    .verify(Duration.ofSeconds(4));
        } finally {
            source.destroy();
        }
    }

    @Test
    void malformedUpdateInvalidatesAllPreviouslyCachedRepresentations() {
        var raw = new AtomicReference<>("1");
        var events = Sinks.many().multicast().<ConfigChange>onBackpressureBuffer();
        when(delegate.watch("")).thenReturn(events.asFlux());
        when(delegate.get(key.key())).thenAnswer(invocation -> Mono.justOrEmpty(raw.get()));
        var source = new CachingConfigSource(delegate, new ConfigCacheProperties(), List.of(key));
        var subscription = source.watchKey(key).subscribe();
        try {
            assertThat(source.get(key).block()).isEqualTo(1);
            assertThat(source.get(key.key()).block()).isEqualTo("1");
            raw.set("invalid");
            events.tryEmitNext(new ConfigChange(ConfigChangeType.PUT, key.key(), raw.get())).orThrow();
            assertThat(source.get(key.key()).block()).isEqualTo("invalid");
            assertThatThrownBy(() -> source.get(key).block())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NumberFormatException").hasMessageNotContaining("invalid")
                    .hasNoCause();
            raw.set("3");
            events.tryEmitNext(new ConfigChange(ConfigChangeType.PUT, key.key(), raw.get())).orThrow();
            assertThat(source.get(key).block()).isEqualTo(3);
        } finally {
            subscription.dispose();
            source.destroy();
        }
    }
}
