package com.otr.portal.identity.token;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Реестр отозванных токенов (jti) в БД.
 * <p>
 * В БД {@code revoked_at} объявлен как {@code TIMESTAMP WITH TIME ZONE}, а драйвер PostgreSQL
 * (pgjdbc) не умеет биндить {@link Instant} через {@code PreparedStatement#setObject} —
 * падает с «Can't infer the SQL type to use for an instance of java.time.Instant».
 * Поэтому на границе с JDBC момент времени передаётся как {@link OffsetDateTime} (UTC),
 * для которого у драйвера есть нативный маппинг в timestamptz.
 */
@Service
@RequiredArgsConstructor
public class TokenRegistry {

    private final JdbcTemplate jdbcTemplate;

    public Mono<Boolean> isRevoked(String jti) {
        return Mono.fromCallable(() -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM token_registry WHERE jti = ?", Integer.class, jti);
            return count != null && count > 0;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> revoke(String jti) {
        var revokedAt = OffsetDateTime.now(ZoneOffset.UTC);
        return Mono.fromRunnable(() -> jdbcTemplate.update(
                        "INSERT INTO token_registry (jti, revoked_at) VALUES (?, ?)", jti, revokedAt))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public Mono<Integer> purgeOlderThan(Instant threshold) {
        var cutoff = threshold.atOffset(ZoneOffset.UTC);
        return Mono.fromCallable(() -> jdbcTemplate.update(
                        "DELETE FROM token_registry WHERE revoked_at < ?", cutoff))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
