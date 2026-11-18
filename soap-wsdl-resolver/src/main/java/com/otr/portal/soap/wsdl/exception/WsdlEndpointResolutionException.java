package com.otr.portal.soap.wsdl.exception;

/**
 * Ошибка резолва SOAP endpoint по WSDL (загрузка/парсинг, отсутствие адреса и т.п.).
 *
 * @author alexraspopin
 * @since 25.12.2025
 */
public class WsdlEndpointResolutionException extends RuntimeException {

    public WsdlEndpointResolutionException(String message) {
        super(message);
    }

    public WsdlEndpointResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
