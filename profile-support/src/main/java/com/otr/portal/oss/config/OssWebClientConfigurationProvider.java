package com.otr.portal.oss.config;

import com.otr.portal.webclient.support.config.WebClientConfiguration;
import com.otr.portal.webclient.support.model.WebClientConfigurationRequest;
import com.otr.portal.webclient.support.model.WebClientType;
import com.otr.portal.webclient.support.registry.WebClientConfigurationProvider;

import java.time.Duration;

/**
 * Конфигурация WebClient для вызовов ОШС/ЮСБ (тип {@link WebClientType#OSS}):
 * пул до 200 соединений, таймаут установки соединения 10 с, таймаут ответа 60 с.
 * Значения перенесены из ручной сборки {@code ossWebClient} без изменений.
 */
public class OssWebClientConfigurationProvider implements WebClientConfigurationProvider {

    private static final int MAX_CONNECTIONS = 200;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    @Override
    public WebClientType type() {
        return WebClientType.OSS;
    }

    @Override
    public WebClientConfiguration provide(WebClientConfigurationRequest request) {
        return WebClientConfiguration.builder()
                .maxConnections(MAX_CONNECTIONS)
                .connectTimeoutMillis(CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(RESPONSE_TIMEOUT)
                .build();
    }
}
