package com.otr.portal.webclient.support.registry;

import com.otr.portal.webclient.support.config.WebClientConfiguration;
import com.otr.portal.webclient.support.model.WebClientConfigurationRequest;
import com.otr.portal.webclient.support.model.WebClientType;

/**
 * Источник конфигурации WebClient для конкретного типа клиента.
 *
 * @author alexraspopin
 * @since 09.04.2026
 */
public interface WebClientConfigurationProvider {
    WebClientType type();

    WebClientConfiguration provide(WebClientConfigurationRequest request);
}
