package com.otr.portal.soap.client.exception;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

import java.net.URI;

/**
 * Ошибка SOAP endpoint с HTTP-ответом.
 */
@Getter
public class SoapClientResponseException extends SoapClientException {
    private final URI endpointUri;
    private final HttpStatusCode statusCode;
    private final String responseBody;

    public SoapClientResponseException(URI endpointUri, HttpStatusCode statusCode, String responseBody) {
        super("SOAP endpoint " + endpointUri + " returned HTTP " + statusCode.value());
        this.endpointUri = endpointUri;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }
}
