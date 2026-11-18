package com.otr.portal.config.source;

import java.util.Objects;
import java.util.function.Function;

/**
 * Типизированный дескриптор ключа конфигурации: имя + класс значения + парсер.
 * <p>
 * Единая точка объявления ключа: через него читают ({@link ConfigSource#get(ConfigKey)}),
 * подписываются ({@link WatchableConfigSource#watchValue(ConfigKey)}) и регистрируют ключ
 * как отслеживаемый в кэшируюшем слое - приложение объявляет такие дескрипторы бинами,
 * и кэш хранит значения уже десериализованными, обновляя их watch-событиями проактивно.
 *
 * @param key    логический ключ в источнике
 * @param type   класс значения; вместе с ключом идентифицирует кэшируемое представление
 * @param parser детерминированный парсер строки в объект (например, Jackson)
 * @author alexraspopin
 */
public record ConfigKey<T>(String key, Class<T> type, Function<String, T> parser) {

    public ConfigKey {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(parser, "parser");
    }

    /**
     * Ключ, значение которого используется как сырая строка (без десериализации).
     */
    public static ConfigKey<String> ofString(String key) {
        return new ConfigKey<>(key, String.class, Function.identity());
    }

    public static <T> ConfigKey<T> of(String key, Class<T> type, Function<String, T> parser) {
        return new ConfigKey<>(key, type, parser);
    }
}
