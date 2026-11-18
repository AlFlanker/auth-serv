package com.otr.portal.etcd;

import io.etcd.jetcd.Client;
import io.opentelemetry.api.OpenTelemetry;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Публичная точка входа для оборачивания {@link Client} трассирующим декоратором.
 * Сами декораторы ({@link TracedEtcdClient} и др.) намеренно package-private.
 *
 * @author alexraspopin
 */
@UtilityClass
public final class EtcdTracing {

    /**
     * Обернуть клиент jetcd декоратором с трассировкой OpenTelemetry.
     *
     * @param client        исходный клиент
     * @param openTelemetry источник трассировки
     * @param endpoints     адреса узлов (для атрибута server.address)
     * @return трассируемый клиент
     */
    public static Client trace(Client client, OpenTelemetry openTelemetry, List<String> endpoints) {
        return new TracedEtcdClient(client, openTelemetry, endpoints);
    }
}
