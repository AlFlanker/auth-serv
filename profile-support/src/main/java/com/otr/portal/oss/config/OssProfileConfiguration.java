package com.otr.portal.oss.config;

import com.otr.portal.soap.wsdl.SoapWebServiceEndpointResolver;
import com.otr.portal.webclient.support.factory.WebClientFactory;
import com.otr.portal.webclient.support.factory.WebClientFactoryContext;
import com.otr.portal.webclient.support.model.WebClientType;
import com.otr.portal.webclient.support.registry.WebClientConfigurationRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Конфигурация инфраструктуры профиля: WebClient для ОШС/ЮСБ (стандартная
 * фабрика webclient-support, тип {@link WebClientType#OSS}), резолвер WSDL,
 * свойства USS.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UsbProperties.class)
public class OssProfileConfiguration {

    private static final String POOL_PREFIX = "oss-profile";

    @Bean
    @Qualifier("ossWebClient")
    WebClient ossWebClient(WebClientFactory webClientFactory,
                           WebClientConfigurationRegistry webClientConfigurationRegistry) {
        return webClientFactory.buildWebClient(WebClientFactoryContext.builder()
                .poolPrefix(POOL_PREFIX)
                .configuration(webClientConfigurationRegistry.provide(WebClientType.OSS, null))
                .build());
    }

    @Bean
    OssWebClientConfigurationProvider ossWebClientConfigurationProvider() {
        return new OssWebClientConfigurationProvider();
    }

    @Bean
    SoapWebServiceEndpointResolver ossWsdlResolver(WebClient.Builder builder) {
        return new SoapWebServiceEndpointResolver(builder);
    }
}
