package com.otr.portal.config.source.etcd;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки ETCD-реализации {@link com.otr.portal.config.source.ConfigSource}
 * (секция {@code config.source.etcd} в application.yml).
 *
 * @author alexraspopin
 */
@Data
@ConfigurationProperties(prefix = "config.source.etcd")
public class EtcdConfigProperties {

    /**
     * Префикс логических ключей внутри ETCD (например {@code nav-composer/}).
     * Аналог {@code appName + "/"} из старого модуля cloud-settings-etcd3. Наружу префикс не виден.
     */
    private String keyPrefix = "";

    /**
     * Таймаут одной операции чтения из ETCD.
     */
    private Duration timeout = Duration.ofSeconds(10);

    /**
     * Требовать наличие лидера кластера для watch ({@code WatchOption.withRequireLeader}).
     * При потере лидера (network partition) watch упадёт с ошибкой и будет переподключён
     * ретраем вместо тихого зависания на отпартиционированном узле.
     */
    private boolean watchRequireLeader = true;
}
