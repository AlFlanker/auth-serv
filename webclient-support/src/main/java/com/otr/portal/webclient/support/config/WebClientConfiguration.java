package com.otr.portal.webclient.support.config;

import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.time.Duration;

/**
 * Конфигурация для создания WebClient. <br>Возможно стоит отказать от излишней прослойки</br>
 * Содержит все параметры настройки connection pool, таймаутов и т.д.
 *
 * @author alexraspopin
 * @since 26.12.2025
 */
@Value
@Builder
public class WebClientConfiguration {

    /**
     * Максимальное количество соединений в пуле.
     * По умолчанию: 1000
     */
    @With
    @Builder.Default
    Integer maxConnections = 1000;

    /**
     * Максимальное количество запросов, ожидающих в очереди, если пул полон.
     * По умолчанию: 20
     */
    @Builder.Default
    Integer pendingAcquireMaxCount = 20;

    /**
     * Таймаут ожидания получения соединения из пула.
     * Если запрос попадает в очередь, то ждет 500 мс.
     * По умолчанию: 500 мс
     */
    @Builder.Default
    Duration pendingAcquireTimeout = Duration.ofMillis(500);

    /**
     * Максимальное время жизни соединения.
     * Периодически пересоздаются соединения (чтобы не убил Firewall).
     * По умолчанию: 10 минут
     */
    @Builder.Default
    Duration maxLifeTime = Duration.ofMinutes(10);

    /**
     * Максимальное время простоя соединения без использования.
     * По умолчанию: 20 секунд
     */
    @Builder.Default
    Duration maxIdleTime = Duration.ofSeconds(20);

    /**
     * Таймаут установки соединения.
     * По умолчанию: 5000 миллисекунд
     */
    @Builder.Default
    Integer connectTimeoutMillis = 5000;

    /**
     * Таймаут ожидания ответа от сервера.
     * По умолчанию - null т.е не разрывает чтение(на уровне выше ограничения)
     */
    @With
    @Builder.Default
    Duration responseTimeout = null;

    /**
     * Максимальный размер данных в памяти для буферизации (в байтах).
     * По умолчанию: 10 МБ
     */
    @Builder.Default
    Integer maxInMemorySize = 10 * 1024 * 1024;

    /**
     * Включить keep-alive для HTTP-соединений.
     * По умолчанию: true
     */
    @Builder.Default
    Boolean keepAlive = true;

    /**
     * Вычисляет максимальное количество одновременных запросов для Hystrix semaphore.
     * maxConnections + pendingAcquireMaxCount. Для лимитеров
     */
    public Integer getMaxConcurrentRequests() {
        return maxConnections + pendingAcquireMaxCount;
    }

    /**
     * Возвращает конфигурацию по умолчанию.
     */
    public static WebClientConfiguration defaultConfiguration() {
        return WebClientConfiguration.builder().build();
    }
}
