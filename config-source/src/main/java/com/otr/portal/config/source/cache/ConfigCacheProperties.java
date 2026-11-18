package com.otr.portal.config.source.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки кэширующего/наблюдаемого слоя источника данных (секция {@code config.source.cache}).
 *
 * @author alexraspopin
 */
@Data
@ConfigurationProperties(prefix = "config.source.cache")
public class ConfigCacheProperties {

    /**
     * Включить кэширование поверх источника данных.
     */
    private boolean enabled = true;

    /**
     * Префикс логических ключей, за изменениями которых следит watch для инвалидации кэша.
     * Пусто - весь диапазон источника.
     */
    private String watchPrefix = "";

    /**
     * Максимальное число записей в кэше.
     */
    private long maximumSize = 10_000;

    /**
     * TTL записи кэша
     */
    private Duration expireAfterWrite = Duration.ofMinutes(10);

    /**
     * Начальная задержка backoff при переподключении watch-потока.
     */
    private Duration watchRetryBackoff = Duration.ofSeconds(5);

    /**
     * Максимальная задержка backoff при переподключении watch-потока
     * (без ограничения экспонента растёт неограниченно).
     */
    private Duration watchRetryMaxBackoff = Duration.ofMinutes(1);
}
