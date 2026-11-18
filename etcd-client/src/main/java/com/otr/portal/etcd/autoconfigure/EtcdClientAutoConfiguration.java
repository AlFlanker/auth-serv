package com.otr.portal.etcd.autoconfigure;

import com.otr.portal.etcd.ETCDSslConfigurer;
import com.otr.portal.etcd.EtcdProperties;
import com.otr.portal.etcd.EtcdTracing;
import com.otr.portal.etcd.ReactiveEtcdClient;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.ClientBuilder;
import io.etcd.jetcd.cluster.Member;
import io.opentelemetry.api.OpenTelemetry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.util.StringUtils.hasText;

/**
 * Автоконфигурация модуля etcd-client: создаёт реактивного клиента для подключения к ETCD.
 * Новый стек-аналог старого {@code ETCDSpringConfiguration}: {@link AutoConfiguration} вместо component-scan,
 * {@link EtcdProperties} вместо системного свойства {@code etcd_uri}, опциональная OpenTelemetry-трассировка.
 *
 * @author alexraspopin
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(EtcdProperties.class)
public class EtcdClientAutoConfiguration {

    private static final String TRACER_ENDPOINTS_JOINER = ", ";

    /**
     * Клиент jetcd. При наличии {@link OpenTelemetry} оборачивается трассирующим декоратором.
     */
    @ConditionalOnMissingBean
    @Bean(destroyMethod = "close")
    public Client etcdClient(
            EtcdProperties properties,
            ObjectProvider<ETCDSslConfigurer> sslConfigurer,
            ObjectProvider<OpenTelemetry> openTelemetry
    ) {
        List<String> endpoints = resolveEndpoints(properties);
        log.info("Подключение к ETCD по адресу(-ам) {}", String.join(TRACER_ENDPOINTS_JOINER, endpoints));

        ClientBuilder builder = Client.builder()
                .endpoints(endpoints.toArray(String[]::new));
        applyProperties(builder, properties);
        sslConfigurer.ifAvailable(configurer -> configurer.configure(builder));

        Client client = builder.build();

        OpenTelemetry telemetry = openTelemetry.getIfAvailable();
        Client resultClient = telemetry != null
                ? EtcdTracing.trace(client, telemetry, endpoints)
                : client;

        if (properties.getStartup().isVerifyConnection()) {
            verifyConnection(resultClient, properties, endpoints);
        }
        return resultClient;
    }

    /**
     * Реактивная обёртка над клиентом (Mono/Flux над KV-операциями jetcd).
     */
    @Bean
    @ConditionalOnMissingBean
    public ReactiveEtcdClient reactiveEtcdClient(Client etcdClient) {
        return new ReactiveEtcdClient(etcdClient);
    }

    private List<String> resolveEndpoints(EtcdProperties properties) {
        List<String> endpoints = properties.getEndpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            return List.of("http://127.0.0.1:2379");
        }
        return endpoints;
    }

    private void applyProperties(ClientBuilder builder, EtcdProperties properties) {
        if (hasText(properties.getUser())) {
            builder.user(byteSequence(properties.getUser()));
        }
        if (hasText(properties.getPassword())) {
            builder.password(byteSequence(properties.getPassword()));
        }
        if (hasText(properties.getNamespace())) {
            builder.namespace(byteSequence(properties.getNamespace()));
        }
        if (hasText(properties.getAuthority())) {
            builder.authority(properties.getAuthority());
        }
        if (hasText(properties.getLoadBalancerPolicy())) {
            builder.loadBalancerPolicy(properties.getLoadBalancerPolicy());
        }
        if (properties.getConnectTimeout() != null) {
            builder.connectTimeout(properties.getConnectTimeout());
        }
        if (properties.getKeepAliveTime() != null) {
            builder.keepaliveTime(properties.getKeepAliveTime());
        }
        if (properties.getKeepAliveTimeout() != null) {
            builder.keepaliveTimeout(properties.getKeepAliveTimeout());
        }
        if (properties.getKeepAliveWithoutCalls() != null) {
            builder.keepaliveWithoutCalls(properties.getKeepAliveWithoutCalls());
        }
        if (properties.getWaitForReady() != null) {
            builder.waitForReady(properties.getWaitForReady());
        }
        if (properties.getMaxInboundMessageSize() != null) {
            builder.maxInboundMessageSize(properties.getMaxInboundMessageSize());
        }
    }

    /**
     * Блокирующая проверка доступности кластера на этапе создания бина (не в reactive-цепочке запроса).
     * В отличие от старого модуля не вызывает {@code System.exit}, а прерывает старт исключением.
     */
    private void verifyConnection(Client client, EtcdProperties properties, List<String> endpoints) {
        EtcdProperties.Startup startup = properties.getStartup();
        List<String> members = Mono.fromFuture(() -> client.getClusterClient().listMember())
                .map(response -> response.getMembers().stream().map(Member::getName).toList())
                .timeout(startup.getTimeout())
                .retryWhen(Retry.backoff(startup.getRetryAttempts(), startup.getRetryBackoff())
                        .maxBackoff(startup.getRetryMaxBackoff()))
                .doOnError(error -> log.error("Ошибка подключения к кластеру ETCD {}",
                        String.join(TRACER_ENDPOINTS_JOINER, endpoints), error))
                .block();
        log.info("Подключено к кластеру ETCD: {}", members);
    }

    private static ByteSequence byteSequence(String value) {
        return ByteSequence.from(value, UTF_8);
    }
}
