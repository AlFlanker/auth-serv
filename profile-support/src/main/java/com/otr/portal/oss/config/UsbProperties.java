package com.otr.portal.oss.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Неизменяемые настройки стратегии аутентификации USS (аналог монолитного {@code UsbProperties},
 * {@code admin.security.usb.*} в application.yml).
 */
@ConfigurationProperties(prefix = "admin.security.usb")
public record UsbProperties(
        String applicationCode,
        String resourceTypeCode,
        String operationCode
) {

    public UsbProperties {
        if (applicationCode == null || applicationCode.isBlank()) {
            applicationCode = "ETD_PORTAL";
        }
        if (resourceTypeCode == null || resourceTypeCode.isBlank()) {
            resourceTypeCode = "ETD_ROLE";
        }
        if (operationCode == null || operationCode.isBlank()) {
            operationCode = "ACCESS";
        }
    }
}
