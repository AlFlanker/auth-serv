package com.otr.portal.identity.token;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Плановая очистка реестра отозванных токенов: записи старше TTL токена удаляются
 * (ревокация не должна жить вечно после истечения токена).
 */
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class TokenRegistryMaintenance {

    private final TokenRegistry tokenRegistry;
    private final com.otr.portal.token.TokenProperties tokenProperties;

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void purge() {
        var threshold = Instant.now().minus(tokenProperties.ttl().toSeconds() * 2, ChronoUnit.SECONDS);
        tokenRegistry.purgeOlderThan(threshold)
                .subscribe(deleted -> {
                    if (deleted != null && deleted > 0) {
                        log.info("Очистка реестра отозванных токенов: удалено записей {}", deleted);
                    }
                }, error -> log.warn("Ошибка очистки реестра отозванных токенов", error));
    }
}
