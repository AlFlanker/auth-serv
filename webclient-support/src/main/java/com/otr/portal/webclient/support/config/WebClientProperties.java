package com.otr.portal.webclient.support.config;

import lombok.Data;

import java.time.Duration;

/**
 * Класс с настройками клиентов(настройки соединения/буфер)
 * Необходим для вычитки настроек из application.yml
 *
 * @author alexraspopin
 * @since 27.02.2026
 */
@Data
public class WebClientProperties {
    private Integer maxConnections = 1000;
    private Integer pendingAcquireMaxCount = 20;
    private Duration pendingAcquireTimeout = Duration.ofMillis(500);
    private Duration maxLifeTime = Duration.ofMinutes(10);
    private Duration maxIdleTime = Duration.ofSeconds(20);
    private Integer connectTimeoutMillis = 5000;
    private Duration responseTimeout;
    private Integer maxInMemorySize = 10 * 1024 * 1024;
    private Boolean keepAlive = true;
}
