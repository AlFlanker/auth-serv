package com.otr.portal.etcd;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Настройки подключения к ETCD (секция {@code etcd} в application.yml).
 * Реактивный аналог старого {@code ETCDSpringConfiguration}, где адреса брались из системного свойства {@code etcd_uri}.
 *
 * @author alexraspopin
 */
@Data
@ConfigurationProperties(prefix = "etcd")
public class EtcdProperties {

    /**
     * Адреса узлов ETCD (схема + host + port), например {@code http://127.0.0.1:2379}.
     */
    private List<String> endpoints = List.of("http://127.0.0.1:2379");

    /**
     * Имя пользователя для аутентификации (RBAC). Пусто — без аутентификации.
     */
    private String user;

    /**
     * Пароль для аутентификации (RBAC).
     */
    private String password;

    /**
     * Префикс (namespace) для всех ключей клиента. Пусто — работа с корнем.
     */
    private String namespace;

    /**
     * gRPC authority (SNI/Host), если требуется переопределить.
     */
    private String authority;

    /**
     * Политика балансировки gRPC, например {@code round_robin} или {@code pick_first}.
     */
    private String loadBalancerPolicy;

    /**
     * Таймаут установления соединения.
     */
    private Duration connectTimeout;

    /**
     * Период keep-alive ping'ов.
     */
    private Duration keepAliveTime;

    /**
     * Таймаут ожидания ответа на keep-alive ping.
     */
    private Duration keepAliveTimeout;

    /**
     * Слать keep-alive ping'и при отсутствии активных вызовов.
     */
    private Boolean keepAliveWithoutCalls;

    /**
     * Ждать готовности канала вместо мгновенного падения запроса.
     */
    private Boolean waitForReady;

    /**
     * Максимальный размер входящего gRPC-сообщения (в байтах).
     */
    private Integer maxInboundMessageSize;

    /**
     * Параметры проверки доступности кластера на старте приложения.
     */
    private Startup startup = new Startup();

    /**
     * Настройки стартовой проверки соединения.
     * Аналог блокирующей проверки кластера из старого модуля (профиль k8s), но без {@code System.exit}
     * и включаемой явно. Проверка выполняется один раз на этапе создания бина — это не reactive-цепочка запроса.
     */
    @Data
    public static class Startup {

        /**
         * Проверять доступность кластера ETCD при инициализации бина клиента.
         */
        private boolean verifyConnection = false;

        /**
         * Число повторов стартовой проверки.
         */
        private int retryAttempts = 3;

        /**
         * Начальная задержка backoff между повторами.
         */
        private Duration retryBackoff = Duration.ofSeconds(5);

        /**
         * Максимальная задержка backoff между повторами.
         */
        private Duration retryMaxBackoff = Duration.ofSeconds(10);

        /**
         * Таймаут одной попытки получить список членов кластера.
         */
        private Duration timeout = Duration.ofSeconds(5);
    }
}
