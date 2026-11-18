package com.otr.portal.identity.health;

import com.otr.portal.config.source.ConfigKey;
import com.otr.portal.config.source.ConfigSource;
import com.otr.portal.identity.token.TokenController;
import com.otr.portal.oss.backend.OssBackendFactory;
import com.otr.portal.oss.config.OssConfig;
import com.otr.portal.oss.config.UsbConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.regex.Pattern;

/** Проверяет конфигурацию, которую использует обработчик SSO-входа. */
@Component
@RequiredArgsConstructor
public class SsoConfigurationHealthIndicator implements ReactiveHealthIndicator {
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~A-Za-z0-9-]+");

    private final ConfigSource source;
    private final ConfigKey<OssConfig> ossKey;
    private final ConfigKey<UsbConfig> usbKey;

    @Override
    public Mono<Health> health() {
        return Mono.defer(() -> source.get(ossKey))
                .flatMap(this::check)
                .defaultIfEmpty(down("Missing OSSConfig"))
                .onErrorReturn(down("Configuration unavailable"));
    }

    private Mono<Health> check(OssConfig config) {
        String header = config.headerName();
        if (header == null || !HEADER_NAME.matcher(header).matches()
                || TokenController.AUTH_SOURCE_PASSWORD.equalsIgnoreCase(header)) {
            return Mono.just(down("Invalid SSO header name"));
        }
        String url = System.getenv().getOrDefault(OssBackendFactory.OSS_URL_ENV, config.ossUrl());
        if (!validEndpoint(url) || config.ossType() == OssConfig.OssType.SOBI_REST) {
            return Mono.just(down("Unsupported profile endpoint or backend"));
        }
        if (config.ossType() == OssConfig.OssType.USB) {
            return source.get(usbKey).map(ignored -> Health.up().build())
                    .defaultIfEmpty(down("Missing UsbConfig"));
        }
        return Mono.just(Health.up().build());
    }

    private static boolean validEndpoint(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getPort() <= 65535;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static Health down(String reason) {
        return Health.down().withDetail("reason", reason).build();
    }
}
