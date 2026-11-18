package com.otr.portal.soap.client.port;

import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * Порт выполнения SOAP-запросов.
 * <p>
 * Инкапсулирует сериализацию доменного запроса в XML, обмен через
 * {@link com.otr.portal.soap.client.transport.SoapTransport} и десериализацию
 * ответа. Собирается через {@link #builder()}.
 *
 * @param <T> тип доменного запроса
 * @param <R> тип доменного ответа
 *
 * @author alexraspopin
 */
public interface SoapPort<T, R> {

    /**
     * Выполняет SOAP-запрос и возвращает десериализованный ответ.
     */
    Mono<R> execute(T payload);

    /**
     * Билдер для произвольных типов запроса/ответа.
     */
    static <T, R> SoapPortBuilder<T, R> builder() {
        return new SoapPortBuilder<>();
    }

    /**
     * Билдер для "сырого" строкового обмена XML → XML
     */
    static SoapPortBuilder<String, String> rawBuilder() {
        return SoapPort.<String, String>builder()
                .serializer(Function.identity())
                .deserializer(Function.identity());
    }
}
