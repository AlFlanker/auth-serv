package com.otr.portal.webclient.support.factory;

import com.otr.portal.webclient.support.config.WebClientConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS;
import static java.time.Duration.ofSeconds;

/**
 * Универсальная фабрика WebClient.
 *
 * @author alexraspopin
 * @since 25.12.2025
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebClientFactory implements DisposableBean {
    private final Map<String, LoopResources> loopResourcesRegistry = new ConcurrentHashMap<>();
    private final Map<String, ConnectionProvider> connectionProviderRegistry = new ConcurrentHashMap<>();
    private final WebClient.Builder baseBuilder;

    /**
     * Создаёт WebClient по переданной конфигурации.
     */
    public WebClient buildWebClient(WebClientFactoryContext context) {
        var loopResources = resolveLoopResources(context);
        var webClientConfiguration = context.getConfiguration();

        var httpClient = buildBaseHttpClient(webClientConfiguration,
                context.getPoolPrefix(), loopResources);

        return baseBuilder.clone()
                .clientConnector(
                        new ReactorClientHttpConnector(httpClient))
                .codecs(codecConfigurer ->
                        codecConfigurer
                                .defaultCodecs()
                                .maxInMemorySize(webClientConfiguration.getMaxInMemorySize()))
                .build();
    }

    private LoopResources resolveLoopResources(WebClientFactoryContext config) {
        return loopResourcesRegistry.computeIfAbsent(config.getPoolPrefix(), prefix -> {
            log.trace("Создание LoopResources для '{}'", prefix);
            if (config.useDefaultLoopResources()) {
                return LoopResources.create("netty-" + prefix);
            }
            return LoopResources.create("netty-" + prefix,
                    config.getWorkerCount(), config.isDaemon());
        });
    }

    private HttpClient buildBaseHttpClient(WebClientConfiguration config,
                                           String prefix,
                                           LoopResources channelResources) {
        var connectionProvider = resolveConnectionProvider(prefix, config);

        return HttpClient.create(connectionProvider)
                .runOn(channelResources)
                .option(CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMillis())
                .responseTimeout(config.getResponseTimeout())
                .keepAlive(config.getKeepAlive());
    }

    private ConnectionProvider resolveConnectionProvider(String prefix, WebClientConfiguration config) {
        return connectionProviderRegistry.computeIfAbsent(prefix, name -> {
            log.trace("Создание ConnectionProvider для '{}'", name);
            return ConnectionProvider.builder(name)
                    .maxConnections(config.getMaxConnections())
                    .pendingAcquireMaxCount(config.getPendingAcquireMaxCount())
                    .pendingAcquireTimeout(config.getPendingAcquireTimeout())
                    .maxLifeTime(config.getMaxLifeTime())
                    .maxIdleTime(config.getMaxIdleTime())
                    .evictInBackground(ofSeconds(30))
                    .build();
        });
    }

    @Override
    public void destroy() {
        connectionProviderRegistry.forEach((prefix, provider) -> {
            log.trace("Освобождение ConnectionProvider для '{}'", prefix);
            provider.disposeLater().block(ofSeconds(10));
        });
        connectionProviderRegistry.clear();

        loopResourcesRegistry.forEach((prefix, resources) -> {
            log.trace("Освобождение LoopResources для '{}'", prefix);
            resources.disposeLater().block(ofSeconds(10));
        });
        loopResourcesRegistry.clear();
    }
}
