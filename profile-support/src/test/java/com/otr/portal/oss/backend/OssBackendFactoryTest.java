package com.otr.portal.oss.backend;

import com.otr.portal.config.source.ConfigKey;
import com.otr.portal.config.source.ConfigSource;
import com.otr.portal.oss.config.OssConfig;
import com.otr.portal.oss.config.UsbConfig;
import com.otr.portal.oss.config.UsbProperties;
import com.otr.portal.soap.wsdl.SoapWebServiceEndpointResolver;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OssBackendFactoryTest {

    @Test
    void retriesFailedConstructionWithoutRequiringAConfigurationChange() {
        var source = mock(ConfigSource.class);
        var oss = ConfigKey.of("OSSConfig", OssConfig.class, raw -> null);
        var usb = ConfigKey.of("UsbConfig", UsbConfig.class, raw -> null);
        var config = new OssConfig(OssConfig.OssType.USB, "https://uss.test", null, null, null,
                null, null, null, null, null, null, null, null);
        when(source.get(oss)).thenReturn(Mono.just(config));
        when(source.get(usb)).thenReturn(Mono.error(new IllegalStateException("etcd unavailable")))
                .thenReturn(Mono.just(new UsbConfig(true, UsbConfig.ApiVersion.V2, null, null)));
        var factory = new OssBackendFactory(source, oss, usb, mock(UsbProperties.class),
                WebClient.create(), mock(SoapWebServiceEndpointResolver.class));

        assertThatThrownBy(() -> factory.getBackend().block()).isInstanceOf(IllegalStateException.class);
        var recovered = factory.getBackend().block();
        assertThat(recovered).isInstanceOf(UsbRestBackend.class);
        assertThat(factory.getBackend().block()).isSameAs(recovered);
    }
}
