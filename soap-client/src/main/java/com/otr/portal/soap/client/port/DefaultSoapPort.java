package com.otr.portal.soap.client.port;

import com.otr.portal.soap.client.exception.SoapClientException;
import com.otr.portal.soap.client.exception.SoapClientResponseException;
import com.otr.portal.soap.client.model.SoapRequest;
import com.otr.portal.soap.client.model.SoapResponse;
import com.otr.portal.soap.client.transport.SoapTransport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Реализация {@link SoapPort}, собирающая запрос, делегирующая обмен
 * {@link SoapTransport} и разбирающая ответ.
 *
 * @author alexraspopin
 */
@RequiredArgsConstructor
class DefaultSoapPort<T, R> implements SoapPort<T, R> {
    private final SoapTransport transport;
    private final URI endpointUri;
    private final MediaType contentType;
    private final Function<? super T, String> serializer;
    private final Function<String, ? extends R> deserializer;
    private final List<Consumer<HttpHeaders>> headerCustomizers;

    @Override
    public Mono<R> execute(T payload) {
        return Mono.fromCallable(() -> buildRequest(payload))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(transport::exchange)
                .flatMap(this::handleResponse);
    }

    private SoapRequest buildRequest(T payload) {
        var body = serialize(payload);
        var headers = new HttpHeaders();
        headerCustomizers.forEach(customizer ->
                customizer.accept(headers));
        return SoapRequest.builder()
                .endpointUri(endpointUri)
                .contentType(contentType)
                .body(body)
                .headers(headers)
                .build();
    }

    private String serialize(T payload) {
        try {
            return serializer.apply(payload);
        } catch (SoapClientException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SoapClientException("Failed to serialize SOAP request for " + endpointUri, e);
        }
    }

    private Mono<R> handleResponse(SoapResponse response) {
        if (response.getStatusCode().isError()) {
            return Mono.error(new SoapClientResponseException(
                    endpointUri,
                    response.getStatusCode(),
                    response.getBody()));
        }
        return Mono.<R>fromCallable(() ->
                        deserializer.apply(response.getBody()))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(error ->
                                !(error instanceof SoapClientException),
                        error -> new SoapClientException(
                                "Failed to deserialize SOAP response from " + endpointUri, error));
    }
}
