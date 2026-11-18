package com.otr.portal.oss.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.otr.portal.config.source.ConfigChange;
import com.otr.portal.config.source.ConfigChangeType;
import com.otr.portal.config.source.WatchableConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigKeysWatcherTest {

    private static final String CANARY = "test-only-secret-for-watcher";
    private final Logger logger = (Logger) LoggerFactory.getLogger(ConfigKeysWatcher.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level previousLevel = logger.getLevel();
    private final WatchableConfigSource source = mock(WatchableConfigSource.class);
    private final WatchedConfigKeys keys = new WatchedConfigKeys();

    @BeforeEach
    void captureDebugEvents() {
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void restoreLogger() {
        logger.setLevel(previousLevel);
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void logsMetadataWithoutInitialOrUpdatedConfigurationValues() {
        var key = keys.ossConfigKey();
        String raw = "{\"ossSecureKey\":\"" + CANARY + "\"}";
        when(source.get(key)).thenReturn(Mono.just(key.parser().apply(raw)));
        when(source.watchKeyIncludingSync(key.key())).thenReturn(Flux.just(new ConfigChange(ConfigChangeType.PUT, key.key(), raw)).concatWith(Flux.never()));
        var watcher = new ConfigKeysWatcher(source, List.of(key), event -> {});
        try {
            watcher.start();
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .contains("Конфигурация 'OSSConfig' загружена: OssConfig", "Конфигурация 'OSSConfig' актуализирована (PUT)");
            assertNoSecretLogged();
        } finally {
            watcher.stop();
        }
    }

    @Test
    void retryLogsErrorTypeWithoutParserMessageOrCause() {
        var key = keys.ossConfigKey();
        when(source.get(key)).thenReturn(Mono.error(new IllegalArgumentException(CANARY)));
        when(source.watchKeyIncludingSync(key.key())).thenReturn(Flux.never());
        var watcher = new ConfigKeysWatcher(source, List.of(key), event -> {});
        try {
            watcher.start();
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains(key.key(), "IllegalArgumentException");
            });
            assertNoSecretLogged();
        } finally {
            watcher.stop();
        }
    }

    @Test
    void rawSourceResyncAndLifecycleRestartInvalidateDerivedState() {
        var key = keys.ossConfigKey();
        var events = new ArrayList<Object>();
        var updates = Sinks.many().multicast().<ConfigChange>onBackpressureBuffer(16, false);
        when(source.get(key)).thenReturn(Mono.empty());
        when(source.watchKeyIncludingSync(key.key())).thenReturn(updates.asFlux());
        var watcher = new ConfigKeysWatcher(source, List.of(key), events::add);
        try {
            watcher.start();
            assertThat(events).singleElement().isInstanceOf(OssConfigChangedEvent.class);
            updates.tryEmitNext(new ConfigChange(ConfigChangeType.SYNC, "", null)).orThrow();
            assertThat(events).hasSize(2);
            watcher.stop();
            watcher.start();
            watcher.start();
            assertThat(events).hasSize(3);
            updates.tryEmitNext(new ConfigChange(ConfigChangeType.DELETE, key.key(), null)).orThrow();
            assertThat(events).hasSize(4).allMatch(OssConfigChangedEvent.class::isInstance);
        } finally {
            watcher.stop();
        }
    }

    private void assertNoSecretLogged() {
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(CANARY);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
}
