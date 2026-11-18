package com.otr.portal.oss.backend;

import com.otr.portal.config.source.ConfigKey;
import com.otr.portal.config.source.ConfigSource;
import com.otr.portal.oss.config.OssConfig;
import com.otr.portal.oss.config.OssConfigChangedEvent;
import com.otr.portal.oss.config.UsbConfig;
import com.otr.portal.oss.config.UsbConfigChangedEvent;
import com.otr.portal.oss.config.UsbProperties;
import com.otr.portal.soap.wsdl.EndpointContext;
import com.otr.portal.soap.wsdl.ResolvedSoapEndpoint;
import com.otr.portal.soap.wsdl.SoapWebServiceEndpointResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Optional.ofNullable;

/**
 * Фабрика бэкендов источника профилей по типу ОШС (аналог монолитного {@code OssBackendFactory}).
 * Бэкенд кэшируется по (ossType|ossUrl) до изменения конфигурации; приоритет имеет env {@code oss.url}.
 */
@Slf4j
@Component
public class OssBackendFactory {

    public static final String OSS_URL_ENV = "oss.url";

    private final ConfigSource configSource;
    private final ConfigKey<OssConfig> ossConfigKey;
    private final ConfigKey<UsbConfig> usbConfigKey;
    private final UsbProperties usbProperties;
    private final WebClient ossWebClient;
    private final SoapWebServiceEndpointResolver wsdlResolver;

    private final Map<String, Mono<OssBackend>> backendCache = new ConcurrentHashMap<>();

    public OssBackendFactory(ConfigSource configSource,
                             ConfigKey<OssConfig> ossConfigKey,
                             ConfigKey<UsbConfig> usbConfigKey,
                             UsbProperties usbProperties,
                             WebClient ossWebClient,
                             SoapWebServiceEndpointResolver wsdlResolver) {
        this.configSource = configSource;
        this.ossConfigKey = ossConfigKey;
        this.usbConfigKey = usbConfigKey;
        this.usbProperties = usbProperties;
        this.ossWebClient = ossWebClient;
        this.wsdlResolver = wsdlResolver;
    }

    public Mono<OssBackend> getBackend() {
        return configSource.get(ossConfigKey)
                .switchIfEmpty(Mono.error(new IllegalStateException("Конфигурация OSSConfig не найдена в etcd")))
                .flatMap(config -> {
                    var url = ofNullable(System.getenv(OSS_URL_ENV))
                            .orElse(config.ossUrl());
                    if (url == null || url.isBlank()) {
                        return Mono.error(new IllegalStateException("Не задан адрес источника профилей (ossUrl)"));
                    }
                    var cacheKey = config.ossType() + "|" + url;
                    // Разделяем успешное построение между запросами. Следующая подписка повторит неудачную загрузку.
                    return backendCache.computeIfAbsent(cacheKey, key ->
                            Mono.defer(() -> buildBackend(config, url)).cacheInvalidateIf(backend -> false));
                });
    }

    @Order(2)
    @EventListener
    public void onOssConfigChanged(OssConfigChangedEvent ignored) {
        log.info("OssConfigChangedEvent: сброс кэша OssBackend");
        backendCache.clear();
    }

    @Order(2)
    @EventListener
    public void onUsbConfigChanged(UsbConfigChangedEvent ignored) {
        log.info("UsbConfigChangedEvent: сброс кэша OssBackend");
        backendCache.clear();
    }

    private Mono<OssBackend> buildBackend(OssConfig config, String url) {
        return switch (config.ossType()) {
            case SOBI_WEB_SERVICE -> buildSobiWsBackend(url);
            case SOBI_REST -> Mono.just(new SobiRestBackend());
            case USB -> configSource.get(usbConfigKey)
                    .switchIfEmpty(Mono.error(new IllegalStateException("Конфигурация UsbConfig не найдена в etcd")))
                    .map(usbConfig -> new UsbRestBackend(ossWebClient, url, usbConfig, usbProperties));
        };
    }

    private Mono<OssBackend> buildSobiWsBackend(String wsdlUrl) {
        return Mono.zip(
                        resolveEndpoint(wsdlUrl, SoapActionType.GET_USER_INFO),
                        resolveEndpoint(wsdlUrl, SoapActionType.GET_USER_GROUPS),
                        resolveEndpoint(wsdlUrl, SoapActionType.MANAGE_USER_CUSTOM_ATTRIBUTES))
                .map(endpoints -> new SobiWsBackend(
                        SoapSupport.userInfoPort(ossWebClient, endpoints.getT1()),
                        SoapSupport.userGroupsPort(ossWebClient, endpoints.getT2()),
                        SoapSupport.manageAttributesPort(ossWebClient, endpoints.getT3())));
    }

    /**
     * Резолвит endpoint (URL + SOAPAction) по WSDL; при сбое — fallback на переданный URL
     * и {@link SoapActionType#getActionName()} (поведение монолита).
     */
    private Mono<ResolvedSoapEndpoint> resolveEndpoint(String wsdlUrl, SoapActionType type) {
        return wsdlResolver.resolveEndpointMetadata(EndpointContext.builder()
                        .url(wsdlUrl)
                        .operationName(type.getOperationName())
                        .build())
                .doOnNext(endpoint -> log.debug("Резолв WSDL {} для операции '{}': endpoint={}, soapAction={}",
                        wsdlUrl, type.getOperationName(), endpoint.getEndpointUrl(), endpoint.getSoapAction()))
                .onErrorResume(e -> {
                    log.warn("Не удалось разрешить WSDL {} для операции '{}', используем fallback: {}",
                            wsdlUrl, type.getOperationName(), e.getMessage());
                    return Mono.just(ResolvedSoapEndpoint.builder()
                            .endpointUrl(wsdlUrl)
                            .soapAction(type.getActionName())
                            .build());
                });
    }
}
