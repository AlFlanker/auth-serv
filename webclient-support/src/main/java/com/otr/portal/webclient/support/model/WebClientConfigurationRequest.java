package com.otr.portal.webclient.support.model;

import lombok.Builder;
import lombok.Value;

/**
 * Запрос на получение конфигурации WebClient.
 *
 * @author alexraspopin
 * @since 09.04.2026
 */
@Value
@Builder
public class WebClientConfigurationRequest {
    WebClientType type;

    /**
     * Идентификатор конкретной цели для сценариев,
     * где конфигурация зависит от внешнего ключа.
     * Например, имени backend.
     */
    String targetKey;
}
