package com.otr.portal.oss.config;

import com.otr.portal.config.source.ConfigChange;
import com.otr.portal.config.source.ConfigKey;
import com.otr.portal.config.source.WatchableConfigSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

import static java.util.stream.Collectors.joining;

/**
 * Слежение за ключами конфигурации приложения (реестр — {@link WatchedConfigKeys}):
 * начальная загрузка и реакция на изменения через push-поток {@link WatchableConfigSource}.
 * Кэш актуализируется самим config-source (за реконнект тоже отвечает источник);
 * здесь — только прикладная реакция на изменения (эмит событий конфигурации).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigKeysWatcher implements SmartLifecycle {

    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(5);
    private static final Duration RETRY_MAX_BACKOFF = Duration.ofMinutes(1);

    private final WatchableConfigSource configSource;
    private final List<ConfigKey<?>> watchedKeys;
    private final Disposable.Composite subscriptions = Disposables.composite();
    private final ApplicationEventPublisher publisher;
    private volatile boolean running = false;

    @Override
    public void start() {
        log.info("Старт отслеживания изменений по ключам: {}",
                watchedKeys.stream()
                        .map(ConfigKey::key)
                        .collect(joining(", ")));
        watchedKeys.forEach(key -> subscriptions.add(subscribeTo(key)));
        running = true;
    }

    @Override
    public void stop() {
        log.info("Прекращаем отслеживание изменений по ключам: {}",
                watchedKeys.stream()
                        .map(ConfigKey::key)
                        .collect(joining(", ")));
        subscriptions.dispose();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private <T> Disposable subscribeTo(ConfigKey<T> key) {
        return Flux.defer(() -> {
                    // подписка на изменения ставится до чтения начального значения, иначе события,
                    // пришедшие за время загрузки, были бы потеряны (sink не реплеит прошлое).
                    // Буферизуется последнее актуальное - промежуточные состояния устаревают
                    ConnectableFlux<ConfigChange> updates = configSource.watchKey(key).replay(1);
                    Disposable connection = updates.connect();
                    return configSource.get(key)
                            .doOnNext(value -> log.info("Конфигурация '{}' загружена: {}",
                                    key.key(), key.type().getSimpleName()))
                            .switchIfEmpty(Mono.fromRunnable(() ->
                                    log.warn("Ключ '{}' отсутствует в источнике конфигурации", key.key())))
                            .thenMany(updates)
                            .doFinally(signal -> connection.dispose());
                })
                // временный сбой (например, таймаут etcd на старте) не должен навсегда убивать
                // слежение за ключом: пересобираем всю цепочку (watch + начальная загрузка)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, RETRY_BACKOFF)
                        .maxBackoff(RETRY_MAX_BACKOFF)
                        .doBeforeRetry(signal -> log.warn(
                                "Слежение за ключом '{}' оборвано ({}), переподписка (попытка {})",
                                key.key(), signal.failure().getClass().getSimpleName(), signal.totalRetries() + 1)))
                .subscribe(
                        change -> {
                            switch (change.type()) {
                                case PUT, DELETE -> {
                                    publishConfigurationChangedEvent(change.key());
                                    log.info("Конфигурация '{}' актуализирована", key.key());
                                }
                                default -> log.trace("Игнорируем событие '{}'", change.type());
                            }
                        },
                        error -> log.error("Слежение за ключом '{}' остановлено окончательно ({})",
                                key.key(), error.getClass().getSimpleName())
                );
    }

    private void publishConfigurationChangedEvent(String key) {
        switch (key) {
            case "OSSConfig" -> publisher.publishEvent(new OssConfigChangedEvent());
            case "UsbConfig" -> publisher.publishEvent(new UsbConfigChangedEvent());
            default -> {}
        }
    }
}
