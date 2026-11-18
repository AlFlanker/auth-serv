package com.otr.portal.webclient.support.config;

import lombok.experimental.UtilityClass;

/**
 * Утилиты для формирования конфигурации WebClient.
 *
 * @author alexraspopin
 * @since 09.04.2026
 */
@UtilityClass
public final class WebClientConfigurations {
    /**
     * Преобразует свойства из application.yml в {@link WebClientConfiguration}.
     */
    public static WebClientConfiguration fromProperties(WebClientProperties settings) {
        if (settings == null) {
            return WebClientConfiguration.defaultConfiguration();
        }

        WebClientConfiguration.WebClientConfigurationBuilder builder =
                WebClientConfiguration.builder();

        if (settings.getMaxConnections() != null) {
            builder.maxConnections(settings.getMaxConnections());
        }
        if (settings.getPendingAcquireMaxCount() != null) {
            builder.pendingAcquireMaxCount(settings.getPendingAcquireMaxCount());
        }
        if (settings.getPendingAcquireTimeout() != null) {
            builder.pendingAcquireTimeout(settings.getPendingAcquireTimeout());
        }
        if (settings.getMaxLifeTime() != null) {
            builder.maxLifeTime(settings.getMaxLifeTime());
        }
        if (settings.getMaxIdleTime() != null) {
            builder.maxIdleTime(settings.getMaxIdleTime());
        }
        if (settings.getConnectTimeoutMillis() != null) {
            builder.connectTimeoutMillis(settings.getConnectTimeoutMillis());
        }
        if (settings.getResponseTimeout() != null) {
            builder.responseTimeout(settings.getResponseTimeout());
        }
        if (settings.getMaxInMemorySize() != null) {
            builder.maxInMemorySize(settings.getMaxInMemorySize());
        }
        if (settings.getKeepAlive() != null) {
            builder.keepAlive(settings.getKeepAlive());
        }

        return builder.build();
    }
}
