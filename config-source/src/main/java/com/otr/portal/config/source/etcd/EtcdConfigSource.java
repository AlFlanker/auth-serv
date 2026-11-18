package com.otr.portal.config.source.etcd;

import com.otr.portal.config.source.ConfigChange;
import com.otr.portal.config.source.ConfigChangeType;
import com.otr.portal.config.source.ConfigEntry;
import com.otr.portal.config.source.WatchableConfigSource;
import com.otr.portal.etcd.ReactiveEtcdClient;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * ETCD-реализация read-only источника данных поверх реактивного {@link ReactiveEtcdClient}.
 * Преобразует типы jetcd в нейтральные ({@link String}/{@code byte[]}/{@link ConfigEntry}/{@link ConfigChange}),
 * применяет префикс ключей и таймаут из {@link EtcdConfigProperties}.
 *
 * @author alexraspopin
 */
@Slf4j
@RequiredArgsConstructor
public class EtcdConfigSource implements WatchableConfigSource {

    private final ReactiveEtcdClient etcdClient;
    private final EtcdConfigProperties properties;

    @Override
    public Mono<String> get(String key) {
        return etcdClient.get(fullKey(key))
                .flatMapIterable(GetResponse::getKvs)
                .next()
                .map(kv ->
                        kv.getValue().toString(UTF_8))
                .timeout(properties.getTimeout());
    }

    @Override
    public Mono<byte[]> getBytes(String key) {
        return etcdClient.get(fullKey(key))
                .flatMapIterable(GetResponse::getKvs)
                .next()
                .map(kv ->
                        kv.getValue().getBytes())
                .timeout(properties.getTimeout());
    }

    @Override
    public Mono<Boolean> exists(String key) {
        return etcdClient.get(fullKey(key))
                .map(response ->
                        response.getCount() > 0)
                .timeout(properties.getTimeout());
    }

    @Override
    public Flux<ConfigEntry> list(String keyPrefix) {
        return etcdClient.getByPrefix(fullKey(keyPrefix))
                .map(kv ->
                        new ConfigEntry(
                                stripPrefix(kv.getKey().toString(UTF_8)),
                                kv.getValue().toString(UTF_8)
                        ))
                .timeout(properties.getTimeout());
    }

    /**
     * Перед стартом watch фиксируется текущая ревизия хранилища,
     * watch поднимается с {@code revision + 1}, а первым событием эмитится {@link ConfigChangeType#SYNC}:
     * всё, что изменится после зафиксированной ревизии, гарантированно попадёт в поток, даже если
     * физическая подписка на сервере установится позже (сервер воспроизведёт историю с ревизии).
     * Это закрывает гонку load-before-watch у потребителей, читающих значения между подпиской
     * и фактическим установлением watch.
     * <p>
     */
    @Override
    public Flux<ConfigChange> watch(String keyPrefix) {
        GetOption revisionProbe = GetOption.builder()
                .isPrefix(true)
                .withCountOnly(true)
                .build();
        return etcdClient.get(fullKey(keyPrefix), revisionProbe)
                .timeout(properties.getTimeout())
                .flatMapMany(response -> {
                    WatchOption option = WatchOption.builder()
                            .isPrefix(true)
                            .withRevision(response.getHeader().getRevision() + 1)
                            .withRequireLeader(properties.isWatchRequireLeader())
                            .build();
                    return etcdClient.watch(fullKey(keyPrefix), option)
                            .mapNotNull(this::toConfigChange)
                            .startWith(new ConfigChange(ConfigChangeType.SYNC, keyPrefix, null));
                });
    }

    private ConfigChange toConfigChange(WatchEvent event) {
        KeyValue keyValue = event.getKeyValue();
        String key = stripPrefix(keyValue.getKey().toString(UTF_8));
        return switch (event.getEventType()) {
            case PUT -> {
                String value = keyValue.getValue().toString(UTF_8);
                log.trace("Событие изменения конфигурации: type=PUT, key={}, valueLength={}", key, value.length());
                yield new ConfigChange(ConfigChangeType.PUT, key, value);
            }
            case DELETE -> {
                log.trace("Событие изменения конфигурации: type=DELETE, key={}", key);
                yield new ConfigChange(ConfigChangeType.DELETE, key, null);
            }
            case UNRECOGNIZED -> {
                log.warn("Пропущено нераспознанное watch-событие источника: key={}", key);
                yield null;
            }
        };
    }

    private ByteSequence fullKey(String key) {
        return ByteSequence.from(toStorageKey(key), UTF_8);
    }

    /**
     * Ключи снаружи всегда логические: префикс хранилища добавляется безусловно.
     */
    private String toStorageKey(String key) {
        return properties.getKeyPrefix() + key;
    }

    private String stripPrefix(String storageKey) {
        String prefix = properties.getKeyPrefix();
        if (!prefix.isEmpty() && storageKey.startsWith(prefix)) {
            return storageKey.substring(prefix.length());
        }
        return storageKey;
    }
}
