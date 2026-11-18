package com.otr.portal.config.source;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * Абстракция read-only источника данных (конфигураций).
 * <p>
 * @author alexraspopin
 */
public interface ConfigSource {

    /**
     * Прочитать значение по ключу как строку.
     *
     * @param key логический ключ
     * @return значение или {@link Mono#empty()}, если ключ отсутствует
     */
    Mono<String> get(String key);

    Mono<byte[]> getBytes(String key);

    /**
     * Прочитать и распарсить значение в объект.
     * Базовая реализация парсит на каждый вызов; кэширующие реализации могут хранить
     * распарсенный объект и не десериализовывать его повторно.
     *
     * @param key    логический ключ
     * @param type   класс результата; вместе с ключом идентифицирует кэшируемое представление
     * @param parser детерминированный парсер строки в объект (например, Jackson);
     *               для одной пары (key, type) все вызывающие должны использовать эквивалентный парсер
     * @return распарсенное значение или {@link Mono#empty()}, если ключ отсутствует
     */
    default <T> Mono<T> get(String key, Class<T> type, Function<String, T> parser) {
        return get(key).map(parser);
    }

    /**
     * Прочитать и распарсить значение по типизированному дескриптору ключа.
     */
    default <T> Mono<T> get(ConfigKey<T> key) {
        return get(key.key(), key.type(), key.parser());
    }

    /**
     * Проверить наличие ключа.
     */
    Mono<Boolean> exists(String key);

    /**
     * Прочитать все пары ключ-значение по префиксу ключа.
     *
     * @param keyPrefix префикс логического ключа
     * @return поток записей (может быть пустым)
     */
    Flux<ConfigEntry> list(String keyPrefix);
}
