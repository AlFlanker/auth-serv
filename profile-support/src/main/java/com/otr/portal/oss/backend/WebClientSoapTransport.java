package com.otr.portal.oss.backend;

import com.otr.portal.soap.client.exception.SoapClientTransportException;
import com.otr.portal.soap.client.model.SoapRequest;
import com.otr.portal.soap.client.model.SoapResponse;
import com.otr.portal.soap.client.transport.SoapTransport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

/**
 * Транспорт SOAP поверх WebClient (точка «встречи» soap-client и WebClient).
 */
@RequiredArgsConstructor
public class WebClientSoapTransport implements SoapTransport {

    private final WebClient webClient;

    @Override
    public Mono<SoapResponse> exchange(SoapRequest request) {
        return webClient.post()
                .uri(request.getEndpointUri())
                .contentType(request.getContentType())
                .headers(headers -> headers.addAll(request.getHeaders()))
                .bodyValue(request.getBody())
                .exchangeToMono(this::toSoapResponse)
                .onErrorMap(WebClientRequestException.class,
                        exception -> new SoapClientTransportException(request.getEndpointUri(), exception));
    }

    private Mono<SoapResponse> toSoapResponse(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> SoapResponse.builder()
                        .statusCode(response.statusCode())
                        .headers(HttpHeaders.readOnlyHttpHeaders(response.headers().asHttpHeaders()))
                        .body(body)
                        .build());
    }
}
