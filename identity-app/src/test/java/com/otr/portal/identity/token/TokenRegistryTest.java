package com.otr.portal.identity.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenRegistryTest {
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private TokenRegistry registry;
    private Instant expiration;

    DriverManagerDataSource createDataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000"
                + ";INIT=CREATE ALIAS IF NOT EXISTS CLOCK_TIMESTAMP FOR 'java.time.Instant.now()'", "sa", "");
    }

    String sessionIdSql() {
        return "SELECT SESSION_ID()";
    }

    String blockedSessionsSql() {
        return "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID = ?";
    }

    @BeforeEach
    void prepareRegistry() {
        source = createDataSource();
        jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__init.sql"),
                new ClassPathResource("db/migration/V2__token_registry_expiry.sql")).execute(source);
        jdbc.update("INSERT INTO token_registry (jti, revoked_at) VALUES ('legacy', CURRENT_TIMESTAMP)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__register_issued_tokens.sql")).execute(source);
        registry = new TokenRegistry(jdbc, new DataSourceTransactionManager(source));
        expiration = Instant.now().plusSeconds(300);
    }

    @Test
    void concurrentExchangeHasOneWinnerAndLogoutOnlyRevokesThePresentedToken() {
        registry.register("prior", expiration).block();
        var results = Flux.range(0, 12)
                .flatMap(i -> registry.exchange("prior", "successor-" + i, expiration)
                        .filter(Boolean::booleanValue).map(ok -> "successor-" + i))
                .collectList().block();
        assertThat(results).hasSize(1);
        assertThat(registry.isActive("prior").block()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM token_registry WHERE revoked_at IS NULL", Integer.class))
                .isOne();
        String winner = results.getFirst();
        registry.revoke("prior").block();
        assertThat(registry.isActive(winner).block()).isTrue();
        registry.revoke(winner).block();
        registry.revoke(winner).block();
        assertThat(registry.isActive(winner).block()).isFalse();
        assertThat(registry.exchange(winner, "another", expiration).block()).isFalse();
    }

    @Test
    void aFailedInsertRollsBackConsumptionAndThePriorCanBeRetried() {
        registry.register("prior", expiration).block();
        registry.register("collision", expiration).block();
        assertThatThrownBy(() -> registry.exchange("prior", "collision", expiration).block())
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> registry.exchange("prior", "expired", Instant.EPOCH).block())
                .isInstanceOf(BadJwtException.class);
        assertThat(registry.exchange("prior", "replacement", expiration).block()).isTrue();
        assertThat(registry.isActive("replacement").block()).isTrue();
    }

    @Test
    void purgeRetainsTheLegacyDenylistAndHonorsSkew() {
        assertThat(registry.exchange("missing", "next", expiration).block()).isFalse();
        assertThat(registry.exchange("legacy", "next", expiration).block()).isFalse();
        jdbc.update("INSERT INTO token_registry (jti, expires_at) VALUES ('expired', clock_timestamp() - INTERVAL '5' SECOND)");
        assertThat(registry.purgeExpired(Duration.ofMinutes(1)).block()).isZero();
        assertThat(registry.purgeExpired(Duration.ZERO).block()).isOne();
        assertThat(jdbc.queryForList("SELECT jti FROM token_registry", String.class)).containsExactly("legacy");
    }

    @Test
    void rechecksDatabaseClockAfterWaitingForThePriorRow() throws Exception {
        registry.register("prior", expiration).block();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT jti FROM token_registry WHERE jti = 'prior' FOR UPDATE");
                 var row = lock.executeQuery()) {
                assertThat(row.next()).isTrue();
            }
            int lockerId;
            try (var statement = connection.createStatement(); var id = statement.executeQuery(sessionIdSql())) {
                id.next();
                lockerId = id.getInt(1);
            }
            var pending = registry.exchange("prior", "successor", expiration).toFuture();
            // Ждём подтверждения БД, что транзакция exchange началась и заблокировалась.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (jdbc.queryForObject(blockedSessionsSql(), Integer.class, lockerId) == 0) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError("exchange did not reach the database lock");
                }
                Thread.sleep(10);
            }
            // exp находится строго между началом exchange и получением блокировки.
            try (var expire = connection.prepareStatement("UPDATE token_registry SET expires_at = clock_timestamp() WHERE jti = 'prior'")) {
                expire.executeUpdate();
            }
            connection.commit();
            assertThat(pending.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(registry.isActive("successor").block()).isFalse();
        }
    }
}
