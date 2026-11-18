package com.otr.portal.soap.client.exception;

/**
 * Ошибка сериализации/десериализации SOAP-конверта: битый XML,
 * отсутствие обязательных элементов ({@code Envelope}/{@code Body}), ошибка JAXB.
 */
public class SoapCodecException extends SoapClientException {

    public SoapCodecException(String message) {
        super(message);
    }

    public SoapCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
