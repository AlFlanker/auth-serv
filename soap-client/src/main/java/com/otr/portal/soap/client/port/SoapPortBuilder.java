package com.otr.portal.soap.client.port;

import com.otr.portal.soap.client.transport.SoapTransport;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.otr.portal.soap.client.model.SoapHeaders.SOAP_ACTION;
import static org.springframework.http.MediaType.TEXT_XML;
import static org.springframework.util.StringUtils.hasText;

/**
 * Билдер {@link SoapPort}.
 * <p>
 * Все зависимости передаются лямбдами, что позволяет собирать порт
 * без привязки к конкретному транспорту или формату сериализации.
 *
 * @param <T> тип доменного запроса
 * @param <R> тип доменного ответа
 *
 * @author alexraspopin
 */
@NoArgsConstructor
public final class SoapPortBuilder<T, R> {
    private SoapTransport transport;
    private URI endpointUri;
    private MediaType contentType = TEXT_XML;
    private Function<? super T, String> serializer;
    private Function<String, ? extends R> deserializer;
    private final List<Consumer<HttpHeaders>> headerCustomizers =
            new ArrayList<>();

    /**
     * Транспорт, выполняющий фактический HTTP-обмен (обязателен).
     */
    public SoapPortBuilder<T, R> transport(@NonNull SoapTransport transport) {
        this.transport = transport;
        return this;
    }

    /**
     * Адрес SOAP endpoint (обязателен).
     */
    public SoapPortBuilder<T, R> endpointUri(@NonNull URI endpointUri) {
        this.endpointUri = endpointUri;
        return this;
    }

    /**
     * Адрес SOAP endpoint (обязателен).
     */
    public SoapPortBuilder<T, R> endpointUri(@NonNull String endpointUri) {
        return endpointUri(URI.create(endpointUri));
    }

    /**
     * Content-Type тела запроса. По умолчанию {@code text/xml}.
     */
    public SoapPortBuilder<T, R> contentType(MediaType contentType) {
        this.contentType = contentType;
        return this;
    }

    /**
     * Сериализатор доменного запроса.
     */
    public SoapPortBuilder<T, R> serializer(@NonNull Function<? super T, String> serializer) {
        this.serializer = serializer;
        return this;
    }

    /**
     * Десериализатор ответа в доменный объект.
     */
    public SoapPortBuilder<T, R> deserializer(@NonNull Function<String, ? extends R> deserializer) {
        this.deserializer = deserializer;
        return this;
    }

    /**
     * Добавляет кастомайзер заголовков. Кастомайзеры применяются в порядке добавления.
     */
    public SoapPortBuilder<T, R> headerCustomizer(Consumer<HttpHeaders> headerCustomizer) {
        if (headerCustomizer != null) {
            headerCustomizers.add(headerCustomizer);
        }
        return this;
    }

    /**
     * Проставляет заголовок {@code SOAPAction}.
     */
    public SoapPortBuilder<T, R> soapAction(String soapAction) {
        return headerCustomizer(headers -> {
            if (hasText(soapAction)) {
                headers.set(SOAP_ACTION, soapAction);
            }
        });
    }

    /**
     * Проставляет заголовок {@code Accept}.
     */
    public SoapPortBuilder<T, R> accept(MediaType accept) {
        return headerCustomizer(headers -> {
            if (accept != null) {
                headers.setAccept(List.of(accept));
            }
        });
    }

    /**
     * Добавляет произвольный статический заголовок.
     */
    public SoapPortBuilder<T, R> header(String name, String... values) {
        return headerCustomizer(headers -> headers.addAll(name, List.of(values)));
    }

    public SoapPort<T, R> build() {
        Assert.notNull(transport, "транспорт не должен быть null");
        Assert.notNull(endpointUri, "endpointUri не должен быть null");
        Assert.notNull(serializer, "сериализатор не должен быть null");
        Assert.notNull(deserializer, "десериализатор не должен быть null");
        return new DefaultSoapPort<>(
                transport,
                endpointUri,
                contentType == null
                        ? TEXT_XML
                        : contentType,
                serializer,
                deserializer,
                List.copyOf(headerCustomizers));
    }
}
