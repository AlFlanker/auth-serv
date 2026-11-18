package com.otr.portal.oss.config;

import com.otr.portal.config.source.ConfigChange;
import com.otr.portal.config.source.ConfigChangeType;
import com.otr.portal.config.source.WatchableConfigSource;
import com.otr.portal.config.source.cache.CachingConfigSource;
import com.otr.portal.config.source.cache.ConfigCacheProperties;
import com.otr.portal.oss.backend.OssBackendFactory;
import com.otr.portal.soap.wsdl.SoapWebServiceEndpointResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import ru.otr.hr.ws.organization.v3.GetUserInfoRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WatchedBackendRecoveryTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void switchesUssPathAfterMissedUpdateOnTheSameBaseUrl(boolean useCache) {
        var keys = new WatchedConfigKeys();
        var oss = keys.ossConfigKey();
        var usb = keys.usbConfigKey();
        var rawSource = mock(WatchableConfigSource.class, CALLS_REAL_METHODS);
        var changes = Sinks.many().multicast().<ConfigChange>onBackpressureBuffer();
        var path = new AtomicReference<>("/old");
        when(rawSource.watch(anyString())).thenReturn(changes.asFlux());
        when(rawSource.get(oss.key())).thenReturn(Mono.just("{\"ossType\":\"USB\",\"ossUrl\":\"https://uss.test\"}"));
        when(rawSource.get(usb.key())).thenAnswer(invocation -> Mono.just(
                "{\"userInfoPath\":\"" + path.get() + "\",\"apiVersion\":\"V2\"}"));
        var cached = new CachingConfigSource(rawSource, new ConfigCacheProperties(), List.of(oss, usb));
        WatchableConfigSource source = useCache ? cached : rawSource;

        var requests = new ArrayList<String>();
        WebClient http = WebClient.builder().exchangeFunction(request -> {
            requests.add(request.url().getPath());
            return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
        }).build();
        var factory = new OssBackendFactory(source, oss, usb, new UsbProperties(null, null, null),
                http, mock(SoapWebServiceEndpointResolver.class));
        var watcher = new ConfigKeysWatcher(source, List.of(oss, usb), event -> {
            if (event instanceof OssConfigChangedEvent changed) {
                factory.onOssConfigChanged(changed);
            } else if (event instanceof UsbConfigChangedEvent changed) {
                factory.onUsbConfigChanged(changed);
            }
        });
        var userInfo = new GetUserInfoRequest();
        userInfo.setLogin("test");
        try {
            watcher.start();
            factory.getBackend().flatMap(backend -> backend.getUserInfoV1(userInfo)).block();
            path.set("/new");
            changes.tryEmitNext(new ConfigChange(ConfigChangeType.SYNC, "", null)).orThrow();
            factory.getBackend().flatMap(backend -> backend.getUserInfoV1(userInfo)).block();
            assertThat(requests).containsExactly("/old", "/new");
        } finally {
            watcher.stop();
            cached.destroy();
        }
    }
}
