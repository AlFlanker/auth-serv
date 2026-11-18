package com.otr.portal.identity.token;

import com.otr.portal.token.TokenProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Удаляет записи после exp токена с учётом допуска на расхождение часов.
 */
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class TokenRegistryMaintenance {

    private final TokenRegistry tokenRegistry;
    private final TokenProperties tokenProperties;

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void purge() {
        tokenRegistry.purgeExpired(tokenProperties.clockSkew())
                .subscribe(deleted -> {
                    if (deleted != null && deleted > 0) {
                        log.info("Очистка реестра отозванных токенов: удалено записей {}", deleted);
                    }
                }, error -> log.warn("Ошибка очистки реестра отозванных токенов", error));
    }
}
