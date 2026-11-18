package com.otr.portal.webclient.support.autoconfigure;

import com.otr.portal.webclient.support.factory.WebClientFactory;
import com.otr.portal.webclient.support.registry.WebClientConfigurationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Автоконфигурация компонентов модуля webclient-support.
 */
@AutoConfiguration(after = WebClientAutoConfiguration.class)
@Import({WebClientFactory.class, WebClientConfigurationRegistry.class})
public class WebClientSupportAutoConfiguration {
}
