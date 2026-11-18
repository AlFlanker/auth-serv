package com.otr.portal.webclient.support.factory;

import com.otr.portal.webclient.support.config.WebClientConfiguration;
import lombok.Builder;
import lombok.Value;

/**
 * Параметры для создания WebClient через {@link WebClientFactory}.
 *
 * @author alexraspopin
 * @since 27.02.2026
 */
@Value
@Builder
public class WebClientFactoryContext {

    /**
     * Префикс для именования потоков event loop и connection pool.
     */
    String poolPrefix;

    /**
     * Количество worker-потоков в event loop.
     * По умолчанию: количество доступных процессоров.
     */
    @Builder.Default
    int workerCount = 0;

    /**
     * Daemon-потоки для event loop.
     */
    @Builder.Default
    boolean daemon = true;

    /**
     * Конфигурация connection pool и таймаутов.
     */
    @Builder.Default
    WebClientConfiguration configuration = WebClientConfiguration.defaultConfiguration();

    /**
     * Использовать ли дефолтные параметры LoopResources (без указания workerCount).
     */
    public boolean useDefaultLoopResources() {
        return workerCount <= 0;
    }
}
