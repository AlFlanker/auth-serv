package com.otr.portal.config.source.cache;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.otr.portal.config.source.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;
import reactor.util.retry.Retry;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

/**
 * Кэширующий и наблюдаемый декоратор {@link WatchableConfigSource}.
 * <p>
 * @author alexraspopin
 */
@Slf4j
public class CachingConfigSource implements WatchableConfigSource, DisposableBean {

    private final WatchableConfigSource delegate;
    private final ConfigCacheProperties properties;
    private final Map<String, ConfigKey<?>> trackedKeys;
    private final AsyncCache<CacheKey, Object> cache;
    // autoCancel=false: сброс всех подписчиков не терминирует sink, новые подписки продолжают получать события
    private final Sinks.Many<ConfigChange> changes = Sinks
            .many()
            .multicast()
            .onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean destroyed = false;

    private final AtomicReference<Disposable> watchSubscription =
            new AtomicReference<>();

    public CachingConfigSource(
            WatchableConfigSource delegate,
            ConfigCacheProperties properties,
            Collection<ConfigKey<?>> trackedKeys
    ) {
        this.delegate = delegate;
        this.properties = properties;
        this.trackedKeys = trackedKeys.stream()
                .collect(toMap(ConfigKey::key, identity()));
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.getMaximumSize())
                .expireAfterWrite(properties.getExpireAfterWrite())
                .buildAsync();
    }

    @Override
    public Mono<String> get(String key) {
        return get(key, String.class, identity());
    }

    @Override
    public <T> Mono<T> get(String key, Class<T> type, Function<String, T> parser) {
        CacheKey cacheKey = new CacheKey(key, type);
        // suppressCancel: отписка одного ожидающего не должна отменять общую (дедуплицированную) загрузку
        return Mono.fromFuture(
                        () -> cache.get(cacheKey, (ignored, executor) ->
                                load(key, type, parser)),
                        true)
                .map(type::cast);
    }

    @Override
    public Mono<byte[]> getBytes(String key) {
        return delegate.getBytes(key);
    }

    @Override
    public Mono<Boolean> exists(String key) {
        boolean cached = cache.synchronous().asMap().keySet().stream()
                .anyMatch(cacheKey -> cacheKey.key().equals(key));
        if (cached) {
            return Mono.just(true);
        }
        return delegate.exists(key);
    }

    @Override
    public Flux<ConfigEntry> list(String keyPrefix) {
        return delegate.list(keyPrefix);
    }

    @Override
    public Flux<ConfigChange> watch(String keyPrefix) {
        ensureWatching();
        if (!keyPrefix.startsWith(properties.getWatchPrefix())) {
            log.warn("watch('{}') выходит за пределы наблюдаемого диапазона watchPrefix='{}' — "
                    + "часть событий не будет доставлена", keyPrefix, properties.getWatchPrefix());
        }
        return changes.asFlux()
                .filter(change ->
                        change.type() == ConfigChangeType.SYNC || change.key().startsWith(keyPrefix));
    }

    @Override
    public void destroy() {
        destroyed = true;
        Disposable subscription = watchSubscription.getAndSet(null);
        if (subscription != null) {
            subscription.dispose();
        }
        changes.tryEmitComplete();
        cache.synchronous().invalidateAll();
    }

    /**
     * При промахе получаем последнюю версию значения у источника.
     */
    private <T> CompletableFuture<Object> load(String key, Class<T> type, Function<String, T> parser) {
        return delegate.get(key).<Object>map(raw -> {
            try {
                return parser.apply(raw);
            } catch (RuntimeException failure) {
                // Caffeine журналирует исключения асинхронной загрузки вместе со всей цепочкой причин.
                throw new IllegalArgumentException("Не удалось разобрать ключ '%s' как %s (%s)"
                        .formatted(key, type.getSimpleName(), failure.getClass().getSimpleName()));
            }
        }).toFuture();
    }

    private void ensureWatching() {
        if (destroyed || !started.compareAndSet(false, true)) {
            return;
        }
        Disposable subscription = Flux.defer(() ->
                        delegate.watch(properties.getWatchPrefix()))
                .concatWith(Mono.error(() ->
                        new IllegalStateException("Watch-поток источника конфигурации завершился")))
                .doOnNext(this::applyAndPublish)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, properties.getWatchRetryBackoff())
                        .maxBackoff(properties.getWatchRetryMaxBackoff())
                        .doBeforeRetry(signal -> log.warn(
                                "Watch-поток источника конфигурации оборван, переподключение (попытка {}, {})",
                                signal.totalRetries() + 1, signal.failure().getClass().getSimpleName())))
                .subscribe(
                        ignored -> {},
                        error -> log.error("Watch-поток источника конфигурации остановлен окончательно ({})",
                                error.getClass().getSimpleName())
                );
        watchSubscription.set(subscription);
        if (destroyed) {
            subscription.dispose();
        }
    }

    private void applyAndPublish(ConfigChange change) {
        if (change.type() == ConfigChangeType.SYNC) {
            resync();
        } else {
            // Для одного ключа могли закэшироваться String, DTO и ещё незавершённые чтения.
            invalidateAllRepresentations(change.key());
            if (change.type() == ConfigChangeType.PUT && change.value() != null) {
                cache.synchronous().put(new CacheKey(change.key(), String.class), change.value());
                parseTrackedRepresentation(change.key(), change.value());
            }
        }
        publish(change);
    }

    /**
     * Ресинхронизация по точке синхронизации {@link ConfigChangeType#SYNC}: источник гарантирует,
     * что все изменения после неё придут событиями, поэтому именно здесь (а не при подписке)
     * безопасно сбросить возможно устаревший кэш - включая записи, закэшированные чтениями
     * наперегонки с установлением watch, - и прогреть отслеживаемые ключи заново.
     * Источник без SYNC деградирует мягко: без предзагрузки, устаревание ограничено TTL.
     */
    private void resync() {
        cache.asMap().clear();
        preloadTrackedKeys();
        log.debug("Кэш ресинхронизирован по точке синхронизации watch-потока");
    }

    /**
     * Прогрев отслеживаемых ключей при (пере)подключении watch-потока: после ресинхронизации
     * (сброса кэша) читатели сразу получают значения из кэша, не платя за поход в источник.
     * Ошибка предзагрузки не фатальна - ключ загрузится лениво при первом чтении.
     */
    private void preloadTrackedKeys() {
        trackedKeys.values()
                .forEach(key -> get(key)
                        .subscribe(
                                ignored -> log.debug("Отслеживаемый ключ '{}' предзагружен в кэш", key.key()),
                                error -> log.warn("Не удалось предзагрузить отслеживаемый ключ '{}' ({})",
                                        key.key(), error.getClass().getSimpleName())));
    }

    private void parseTrackedRepresentation(String key, String value) {
        var tracked = trackedKeys.get(key);
        if (tracked == null || tracked.type() == String.class) {
            return;
        }
        try {
            Object parsed = tracked.parser().apply(value);
            cache.synchronous().put(new CacheKey(key, tracked.type()), parsed);
        } catch (RuntimeException error) {
            log.error("Не удалось десериализовать обновление ключа '{}' в {} ({})",
                    key, tracked.type().getName(), error.getClass().getSimpleName());
        }
    }

    private void invalidateAllRepresentations(String key) {
        cache.asMap().keySet().removeIf(cacheKey -> cacheKey.key().equals(key));
    }

    private void publish(ConfigChange change) {
        Sinks.EmitResult result = changes.tryEmitNext(change);
        if (result.isFailure()
                && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            log.warn("Событие изменения конфигурации не доставлено подписчикам: key={}, result={}",
                    change.key(), result);
        }
    }

    private record CacheKey(String key, Class<?> type) {
    }
}
