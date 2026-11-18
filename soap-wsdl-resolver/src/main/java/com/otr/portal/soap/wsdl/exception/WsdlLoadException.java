package com.otr.portal.soap.wsdl.exception;

/**
 * Ошибка загрузки WSDL/XSD документа.
 *
 * @author alexraspopin
 * @since 25.12.2025
 */
public class WsdlLoadException extends RuntimeException {
    public WsdlLoadException(String message) {
        super(message);
    }

    public WsdlLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
