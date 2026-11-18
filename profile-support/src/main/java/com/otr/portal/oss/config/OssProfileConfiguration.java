package com.otr.portal.oss.config;

import com.otr.portal.soap.wsdl.SoapWebServiceEndpointResolver;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * Конфигурация инфраструктуры профиля: WebClient для ОШС/ЮСБ, резолвер WSDL, свойства USS.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UsbProperties.class)
public class OssProfileConfiguration {

    @Bean
    @Qualifier("ossWebClient")
    WebClient ossWebClient(WebClient.Builder builder) {
        var httpClient = HttpClient.create(ConnectionProvider.builder("oss-profile")
                        .maxConnections(200)
                        .build())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(60));
        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    SoapWebServiceEndpointResolver ossWsdlResolver(WebClient.Builder builder) {
        return new SoapWebServiceEndpointResolver(builder);
    }
}
