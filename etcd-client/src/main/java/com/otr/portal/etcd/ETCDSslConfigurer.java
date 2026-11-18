package com.otr.portal.etcd;

import io.etcd.jetcd.ClientBuilder;

/**
 * Конфигуратор клиента ETCD.
 * Позволяет приложению настроить SSL/mTLS, не завязывая модуль etcd-client на конкретную реализацию TLS.
 *
 * @author alexraspopin
 * @since 11.12.2025
 */
@FunctionalInterface
public interface ETCDSslConfigurer {

    /**
     * Настроить SSL/mTLS для ETCD, если это требуется.
     *
     * @param clientBuilder билдер клиента jetcd
     */
    void configure(ClientBuilder clientBuilder);
}
