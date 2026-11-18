package com.otr.portal.oss.config;

import com.otr.portal.config.source.ConfigKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Реестр отслеживаемых ключей конфигурации приложения (etcd, префикс {@code fk-portal/}).
 * <p>
 * Каждый бин {@link ConfigKey} автоматически подхватывается кэширующим слоем config-source:
 * ключ предзагружается при старте watch-потока, а его значение проактивно обновляется
 * в кэше по watch-событиям. {@link ConfigKeysWatcher} следит за этим же списком и
 * публикует доменные события изменения конфигураций.
 */
@Configuration(proxyBeanMethods = false)
class WatchedConfigKeys {

    /**
     * Локальный неизменяемый маппер конфигураций (Jackson 3): лишние поля DTO не ломают парсинг.
     * Не объявляется бином, чтобы не подменять глобальный ObjectMapper Spring Boot.
     */
    private static final ObjectMapper CONFIG_MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Bean
    @ConditionalOnMissingBean(name = "ossConfigKey")
    ConfigKey<OssConfig> ossConfigKey() {
        // ключ etcd — по имени класса монолитного OSSConfig (fk-portal/OSSConfig)
        return ConfigKey.of("OSSConfig", OssConfig.class,
                raw -> parse(raw, OssConfig.class));
    }

    @Bean
    @ConditionalOnMissingBean(name = "usbConfigKey")
    ConfigKey<UsbConfig> usbConfigKey() {
        return ConfigKey.of("UsbConfig", UsbConfig.class,
                raw -> parse(raw, UsbConfig.class));
    }

    private static <T> T parse(String raw, Class<T> type) {
        try {
            return CONFIG_MAPPER.readValue(raw, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Не удалось десериализовать конфигурацию " + type.getSimpleName(), e);
        }
    }
}
