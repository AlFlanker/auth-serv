package com.otr.portal.webclient.support.exception;

import com.otr.portal.webclient.support.model.WebClientType;

/**
 * Исключение об отсутствии провайдера конфигурации WebClient для типа клиента.
 *
 * @author alexraspopin
 * @since 09.04.2026
 */
public class WebClientConfigurationProviderNotFoundException extends RuntimeException {
    public WebClientConfigurationProviderNotFoundException(WebClientType type) {
        super("Не найден провайдер конфигурации WebClient для типа " + type);
    }
}
