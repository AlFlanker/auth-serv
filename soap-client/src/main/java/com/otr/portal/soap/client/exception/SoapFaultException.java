package com.otr.portal.soap.client.exception;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * Сервер вернул SOAP Fault вместо ожидаемого ответа.
 * <p>
 * Поддерживаются оба формата: SOAP 1.1 ({@code faultcode}/{@code faultstring})
 * и SOAP 1.2 ({@code Code/Value}, {@code Reason/Text}).
 */
@Getter
public class SoapFaultException extends SoapClientException {

    @Nullable
    private final String faultCode;
    @Nullable
    private final String faultString;

    public SoapFaultException(@Nullable String faultCode, @Nullable String faultString) {
        super("SOAP Fault в ответе сервиса: code='" + faultCode + "', message='" + faultString + "'");
        this.faultCode = faultCode;
        this.faultString = faultString;
    }
}
