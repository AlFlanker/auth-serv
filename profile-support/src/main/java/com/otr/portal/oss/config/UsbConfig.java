package com.otr.portal.oss.config;

/**
 * Конфигурация интеграции с ЮСБ/USS для профиля пользователя (subset монолитного {@code UsbConfig},
 * ключ etcd: {@code UsbConfig}).
 */
public record UsbConfig(
        Boolean usbGroupEnabled,
        ApiVersion apiVersion,
        String userInfoPath,
        String userGroupPath
) {

    public static final String DEFAULT_USER_INFO_PATH = "/identification/v2/employee/findByJwt";
    public static final String DEFAULT_USER_GROUP_PATH = "/authorization/v2/access/resources";

    public UsbConfig {
        if (usbGroupEnabled == null) {
            usbGroupEnabled = false;
        }
        if (apiVersion == null) {
            apiVersion = ApiVersion.V1;
        }
        if (userInfoPath == null || userInfoPath.isBlank()) {
            userInfoPath = DEFAULT_USER_INFO_PATH;
        }
        if (userGroupPath == null || userGroupPath.isBlank()) {
            userGroupPath = DEFAULT_USER_GROUP_PATH;
        }
    }

    /**
     * Версия контракта запросов пользователя и групп USS.
     */
    public enum ApiVersion {
        V1,
        V2
    }
}
