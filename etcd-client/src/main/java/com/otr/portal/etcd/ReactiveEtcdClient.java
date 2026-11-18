package com.otr.portal.etcd;

import io.etcd.jetcd.*;
import io.etcd.jetcd.kv.DeleteResponse;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.kv.PutResponse;
import io.etcd.jetcd.options.DeleteOption;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Реактивный адаптер над key-value API jetcd.
 * <p>
 * Управление ресурсами: {@link KV} и {@link Watch}, возвращаемые {@code getKVClient()}/{@code getWatchClient()},
 * jetcd создаёт лениво и кэширует внутри {@link Client}, поэтому оборачивать их в try-with-resources на каждую
 * операцию нельзя — закрытие стаба закрывает его для всех вызывающих. Единственный владелец ресурсов —
 * бин {@link Client} (закрывается контейнером через {@code destroyMethod = "close"});
 * per-subscription ресурс {@link Watch.Watcher} закрывается в {@link #watch(ByteSequence, WatchOption)}
 * через {@code sink.onDispose}.
 *
 * @author alexraspopin
 */
@Slf4j
@RequiredArgsConstructor
public class ReactiveEtcdClient {

    private final Client client;

    /**
     * Получить значение по ключу.
     */
    public Mono<GetResponse> get(ByteSequence key) {
        return Mono.fromFuture(() ->
                kv().get(key));
    }

    /**
     * Получить значение по ключу с дополнительными опциями.
     */
    public Mono<GetResponse> get(ByteSequence key, GetOption option) {
        return Mono.fromFuture(() ->
                kv().get(key, option));
    }

    /**
     * Прочитать все пары ключ-значение по префиксу.
     */
    public Flux<KeyValue> getByPrefix(ByteSequence prefix) {
        GetOption option = GetOption.builder()
                .isPrefix(true)
                .build();
        return get(prefix, option)
                .flatMapIterable(GetResponse::getKvs);
    }

    /**
     * Записать значение по ключу.
     */
    public Mono<PutResponse> put(ByteSequence key, ByteSequence value) {
        return Mono.fromFuture(() ->
                kv().put(key, value));
    }

    /**
     * Записать значение по ключу с дополнительными опциями.
     */
    public Mono<PutResponse> put(ByteSequence key, ByteSequence value, PutOption option) {
        return Mono.fromFuture(() ->
                kv().put(key, value, option));
    }

    /**
     * Удалить ключ.
     */
    public Mono<DeleteResponse> delete(ByteSequence key) {
        return Mono.fromFuture(() ->
                kv().delete(key));
    }

    /**
     * Удалить ключ с дополнительными опциями.
     */
    public Mono<DeleteResponse> delete(ByteSequence key, DeleteOption option) {
        return Mono.fromFuture(() ->
                kv().delete(key, option));
    }

    /**
     * Получить значение ключа как UTF-8 строку; пустой {@link Mono}, если ключа нет.
     */
    public Mono<String> getValueAsString(String key) {
        return get(toByteSequence(key))
                .flatMapIterable(GetResponse::getKvs)
                .next()
                .map(kv -> kv.getValue().toString(StandardCharsets.UTF_8));
    }

    /**
     * Подписаться на изменения одного ключа.
     * Каждое событие ETCD ({@link WatchEvent}) эмитится отдельным элементом; поток "горячий" и бесконечный,
     * закрывается при отмене подписки (jetcd {@code Watcher} закрывается через {@code sink.onDispose}).
     * Обрывы соединения jetcd переживает сам (watcher возобновляется с актуальной ревизии);
     * фатальные ошибки приходят как {@code onError} — ретрай/ресинхронизацию после них оставляем вызывающему
     * (например, {@code retryWhen} + повторное чтение снапшота).
     */
    public Flux<WatchEvent> watch(ByteSequence key) {
        return watch(key, WatchOption.DEFAULT);
    }

    /**
     * Подписаться на изменения ключей по префиксу.
     */
    public Flux<WatchEvent> watchByPrefix(ByteSequence prefix) {
        WatchOption option = WatchOption.builder()
                .isPrefix(true)
                .build();
        return watch(prefix, option);
    }

    /**
     * Подписаться на изменения с произвольными опциями watch.
     * Стратегия переполнения - {@link FluxSink.OverflowStrategy#BUFFER}: события конфигурации терять нельзя
     * (пропуск DELETE/PUT ломает консистентность кэша у подписчика), а их поток на практике редкий,
     * так что неограниченный буфер безопасен.
     */
    public Flux<WatchEvent> watch(ByteSequence key, WatchOption option) {
        var watchKey = key.toString(StandardCharsets.UTF_8);
        return Flux.create(sink -> {
            Watch.Listener listener = Watch.listener(
                    response -> response.getEvents().forEach(event -> {
                        log.trace("Событие наблюдателя ETCD: watchKey={}, type={}, key={}",
                                watchKey,
                                event.getEventType(),
                                event.getKeyValue().getKey().toString(StandardCharsets.UTF_8));
                        sink.next(event);
                    }),
                    sink::error,
                    sink::complete
            );
            Watch.Watcher watcher = client.getWatchClient().watch(key, option, listener);
            sink.onDispose(watcher::close);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * Низкоуровневый jetcd-клиент (в т.ч. для lease/lock/election и трассируемого KV).
     */
    public Client raw() {
        return client;
    }

    private KV kv() {
        return client.getKVClient();
    }

    private static ByteSequence toByteSequence(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }
}
