package com.otr.portal.soap.client.exception;

/**
 * Базовое исключение SOAP-клиента.
 */
public class SoapClientException extends RuntimeException {
    public SoapClientException(String message) {
        super(message);
    }

    public SoapClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
