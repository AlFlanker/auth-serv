package com.otr.portal.identity.token;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/** Общий для всех реплик реестр выданных JWT. Запись с revoked_at блокирует обмен. */
@Service
public class TokenRegistry {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TokenRegistryProperties properties;

    public TokenRegistry(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, TokenRegistryProperties properties) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setTimeout(Math.toIntExact(properties.transactionTimeout().toSeconds()));
    }

    public Mono<Void> register(String jti, Instant expiresAt) {
        return Mono.fromRunnable(() -> transactions.executeWithoutResult(status -> insertActive(jti, expiresAt)))
                .subscribeOn(Schedulers.boundedElastic()).then();
    }

    /** Быстрая проверка перед обращением к источнику профиля. Окончательное решение принимает exchange. */
    public Mono<Boolean> isActive(String jti) {
        return Mono.fromCallable(() -> transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM token_registry
                                       WHERE jti = ? AND revoked_at IS NULL AND expires_at > clock_timestamp())
                        """, Boolean.class, jti))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Boolean> exchange(String priorJti, String successorJti, Instant successorExpiresAt) {
        return Mono.fromCallable(() -> transactions.execute(status -> {
            // Захватываем блокировку до проверки срока: транзакция могла ждать другой exchange/logout.
            var locked = jdbc.queryForList("SELECT jti FROM token_registry WHERE jti = ? FOR UPDATE",
                    String.class, priorJti);
            if (locked.isEmpty()) {
                return false;
            }
            int consumed = jdbc.update("""
                    UPDATE token_registry SET revoked_at = clock_timestamp()
                     WHERE jti = ? AND revoked_at IS NULL AND expires_at > clock_timestamp()
                    """, priorJti);
            if (consumed == 0) {
                return false;
            }
            // Ошибка вставки откатывает и UPDATE. Токен можно вернуть клиенту только после commit.
            insertActive(successorJti, successorExpiresAt);
            return true;
        })).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> revoke(String jti) {
        return Mono.fromRunnable(() -> transactions.executeWithoutResult(status -> jdbc.update("""
                        UPDATE token_registry SET revoked_at = clock_timestamp()
                         WHERE jti = ? AND revoked_at IS NULL
                        """, jti)))
                .subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void insertActive(String jti, Instant expiresAt) {
        var expiration = expiresAt.atOffset(ZoneOffset.UTC);
        int inserted = jdbc.update("""
                INSERT INTO token_registry (jti, expires_at)
                SELECT ?, CAST(? AS TIMESTAMP WITH TIME ZONE)
                 WHERE CAST(? AS TIMESTAMP WITH TIME ZONE) > clock_timestamp()
                """, jti, expiration, expiration);
        if (inserted == 0) {
            throw new BadJwtException("Cannot register an expired token");
        }
    }

    public Mono<Integer> purgeExpired(Duration clockSkew) {
        var skewSeconds = BigDecimal.valueOf(clockSkew.toSeconds()).add(BigDecimal.valueOf(clockSkew.getNano(), 9));
        return Mono.fromCallable(() -> transactions.execute(status -> {
            var expired = jdbc.queryForList("""
                    SELECT jti FROM token_registry
                     WHERE expires_at < CURRENT_TIMESTAMP - CAST(? AS DOUBLE PRECISION) * INTERVAL '1' SECOND
                     ORDER BY expires_at
                     LIMIT ? FOR UPDATE SKIP LOCKED
                    """, String.class, skewSeconds, properties.purgeBatchSize());
            if (expired.isEmpty()) {
                return 0;
            }
            return namedJdbc.update("DELETE FROM token_registry WHERE jti IN (:expired)", Map.of("expired", expired));
        })).subscribeOn(Schedulers.boundedElastic());
    }
}
