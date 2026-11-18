package com.otr.portal.webclient.support.exception;

import com.otr.portal.webclient.support.model.WebClientType;

/**
 * Исключение о дублировании провайдера конфигурации WebClient для одного типа.
 *
 * @author alexraspopin
 * @since 09.04.2026
 */
public class DuplicateWebClientConfigurationProviderException extends RuntimeException {
    public DuplicateWebClientConfigurationProviderException(WebClientType type) {
        super("Найдено несколько провайдеров конфигурации WebClient для типа " + type);
    }
}
