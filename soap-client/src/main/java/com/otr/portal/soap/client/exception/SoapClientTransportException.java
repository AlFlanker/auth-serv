package com.otr.portal.soap.client.exception;

import lombok.Getter;

import java.net.URI;

/**
 * Ошибка транспортного уровня до получения HTTP-ответа.
 */
@Getter
public class SoapClientTransportException extends SoapClientException {
    private final URI endpointUri;

    public SoapClientTransportException(URI endpointUri, Throwable cause) {
        super("SOAP endpoint " + endpointUri + " недоступен", cause);
        this.endpointUri = endpointUri;
    }
}
