package com.otr.portal.identity.token;

import com.otr.portal.token.TokenProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(TokenRegistryProperties.class)
@RequiredArgsConstructor
public class TokenRegistryMaintenance {

    private final TokenRegistry tokenRegistry;
    private final TokenProperties tokenProperties;

    /** Spring отсчитывает fixedDelay после завершения подписки на весь цикл очистки. */
    @Scheduled(fixedDelayString = "${identity.token.registry.purge-delay:PT1H}",
            initialDelayString = "${identity.token.registry.purge-initial-delay:PT1M}")
    public Mono<Void> purge() {
        return Mono.defer(() -> tokenRegistry.purgeExpired(tokenProperties.clockSkew()))
                .repeat()
                .takeUntil(deleted -> deleted == 0)
                .reduce(0L, (total, deleted) -> total + deleted)
                .doOnNext(total -> {
                    if (total > 0) {
                        log.info("Удалено истёкших записей из реестра JWT: {}", total);
                    }
                })
                .then();
    }
}
