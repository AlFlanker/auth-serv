package com.otr.portal.soap.client.port;

import com.otr.portal.soap.client.exception.SoapClientResponseException;
import com.otr.portal.soap.client.model.SoapRequest;
import com.otr.portal.soap.client.model.SoapResponse;
import com.otr.portal.soap.client.transport.SoapTransport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static com.otr.portal.soap.client.model.SoapHeaders.SOAP_ACTION;
import static org.assertj.core.api.Assertions.assertThat;

class SoapPortTest {

    private static final URI ENDPOINT = URI.create("https://example.test/soap");

    @Test
    void executeSerializesRequestAppliesHeadersAndDeserializesResponse() {
        var captured = new AtomicReference<SoapRequest>();
        SoapTransport transport = request -> {
            captured.set(request);
            return Mono.just(SoapResponse.builder()
                    .statusCode(HttpStatus.OK)
                    .body("<response><value>42</value></response>")
                    .build());
        };

        var port = SoapPort.<Integer, Integer>builder()
                .transport(transport)
                .endpointUri(ENDPOINT)
                .soapAction("urn:testAction")
                .accept(MediaType.TEXT_XML)
                .header("X-Correlation-Id", "42")
                .serializer(value -> "<request>" + value + "</request>")
                .deserializer(body -> Integer.parseInt(
                        body.replaceAll(".*<value>(\\d+)</value>.*", "$1")))
                .build();

        StepVerifier.create(port.execute(7))
                .expectNext(42)
                .verifyComplete();

        var request = captured.get();
        assertThat(request).isNotNull();
        assertThat(request.getEndpointUri()).isEqualTo(ENDPOINT);
        assertThat(request.getBody()).isEqualTo("<request>7</request>");
        assertThat(request.getContentType()).isEqualTo(MediaType.TEXT_XML);
        assertThat(request.getHeaders().getFirst(SOAP_ACTION)).isEqualTo("urn:testAction");
        assertThat(request.getHeaders().getAccept()).containsExactly(MediaType.TEXT_XML);
        assertThat(request.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("42");
    }

    @Test
    void executeMapsErrorStatusToSoapClientResponseException() {
        SoapTransport transport = ignored -> Mono.just(SoapResponse.builder()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("<fault/>")
                .build());

        var port = SoapPort.rawBuilder()
                .transport(transport)
                .endpointUri(ENDPOINT)
                .build();

        StepVerifier.create(port.execute("<request/>"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(SoapClientResponseException.class);
                    var exception = (SoapClientResponseException) error;
                    assertThat(exception.getEndpointUri()).isEqualTo(ENDPOINT);
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(exception.getResponseBody()).isEqualTo("<fault/>");
                })
                .verify();
    }
}
