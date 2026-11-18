package com.otr.portal.oss.exception;

/**
 * Источник профилей (ОШС/ЮСБ) недоступен.
 */
public class OssUnavailableException extends RuntimeException {

    public OssUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public OssUnavailableException(String message) {
        super(message);
    }
}
