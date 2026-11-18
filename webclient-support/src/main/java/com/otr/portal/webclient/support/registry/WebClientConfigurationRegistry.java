package com.otr.portal.webclient.support.registry;

import com.otr.portal.webclient.support.config.WebClientConfiguration;
import com.otr.portal.webclient.support.exception.DuplicateWebClientConfigurationProviderException;
import com.otr.portal.webclient.support.exception.WebClientConfigurationProviderNotFoundException;
import com.otr.portal.webclient.support.model.WebClientConfigurationRequest;
import com.otr.portal.webclient.support.model.WebClientType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;

/**
 * Реестр провайдеров конфигурации WebClient.
 *
 * @author alexraspopin
 * @since 09.04.2026
 */
@Component
public class WebClientConfigurationRegistry {
    private final EnumMap<WebClientType, WebClientConfigurationProvider> providers =
            new EnumMap<>(WebClientType.class);

    public WebClientConfigurationRegistry(List<WebClientConfigurationProvider> providers) {
        for (WebClientConfigurationProvider provider : providers) {
            var previous = this.providers.put(provider.type(), provider);
            if (previous != null) {
                throw new DuplicateWebClientConfigurationProviderException(provider.type());
            }
        }
    }

    public WebClientConfiguration provide(WebClientType type, String targetKey) {
        return provide(WebClientConfigurationRequest.builder()
                .type(type)
                .targetKey(targetKey)
                .build());
    }

    private WebClientConfiguration provide(WebClientConfigurationRequest request) {
        var provider = providers.get(request.getType());
        if (provider == null) {
            throw new WebClientConfigurationProviderNotFoundException(request.getType());
        }
        return provider.provide(request);
    }
}
