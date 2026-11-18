package com.otr.portal.oss.config;

/**
 * Конфигурация интеграции с ОШС (СОБИ).
 * DTO содержит только необходимые сервису поля конфигурации монолита {@code OSSConfig}
 * (ключ etcd: {@code OssConfig}); неизвестные поля игнорируются.
 */
public record OssConfig(
        OssType ossType,
        String ossUrl,
        String ossSecureKey,
        Integer cacheTimeMin,
        String headerName,
        Boolean ossGroupEnabled,
        Integer authoritiesTimeout,
        Integer authoritiesMaxConcurrentRequests,
        Integer userDetailsTimeout,
        Integer userDetailsMaxConcurrentRequests,
        Integer manageUserCustomAttributesTimeout,
        Integer manageUserCustomAttributesMaxConcurrentRequests,
        String orgHeaderName
) {

    public OssConfig {
        if (ossType == null) {
            ossType = OssType.SOBI_WEB_SERVICE;
        }
        if (cacheTimeMin == null || cacheTimeMin <= 0) {
            cacheTimeMin = 60;
        }
        if (ossGroupEnabled == null) {
            ossGroupEnabled = true;
        }
        if (authoritiesTimeout == null || authoritiesTimeout <= 0) {
            authoritiesTimeout = 1;
        }
        if (authoritiesMaxConcurrentRequests == null || authoritiesMaxConcurrentRequests <= 0) {
            authoritiesMaxConcurrentRequests = 20;
        }
        if (userDetailsTimeout == null || userDetailsTimeout <= 0) {
            userDetailsTimeout = 1;
        }
        if (userDetailsMaxConcurrentRequests == null || userDetailsMaxConcurrentRequests <= 0) {
            userDetailsMaxConcurrentRequests = 20;
        }
        if (manageUserCustomAttributesTimeout == null || manageUserCustomAttributesTimeout <= 0) {
            manageUserCustomAttributesTimeout = 5;
        }
        if (manageUserCustomAttributesMaxConcurrentRequests == null || manageUserCustomAttributesMaxConcurrentRequests <= 0) {
            manageUserCustomAttributesMaxConcurrentRequests = 20;
        }
        if (orgHeaderName == null || orgHeaderName.isBlank()) {
            orgHeaderName = "User-Selected-OrgCode";
        }
    }

    /**
     * Тип ОШС (аналог {@code OSSType} монолита).
     */
    public enum OssType {
        SOBI_REST,
        SOBI_WEB_SERVICE,
        USB
    }
}
