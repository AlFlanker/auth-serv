package com.otr.portal.identity.token;

import com.otr.portal.token.TokenProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
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
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000"
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
        jdbc.update("INSERT INTO roles (id, name) VALUES ('ROLE_ADMIN', 'Custom administrator')");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V3__register_issued_tokens.sql"),
                new ClassPathResource("db/migration/V4__builtin_roles.sql")).execute(source);
        registry = new TokenRegistry(jdbc, new DataSourceTransactionManager(source),
                new TokenRegistryProperties(Duration.ofSeconds(2), 2));
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
    void parallelStartupSeedsMissingRolesAndPreservesNames() {
        // Имитируем существующую схему V3, в которой оператор сохранил своё имя роли.
        jdbc.update("DELETE FROM roles WHERE id IN ('ROLE_USER', 'ROLE_EDITOR')");
        Flyway.configure().dataSource(source).baselineVersion("3").load().baseline();
        var start = new CyclicBarrier(2);
        var migrations = Flux.range(0, 2).flatMap(i -> Mono.fromCallable(() -> {
            var flyway = Flyway.configure().dataSource(source).load();
            start.await(5, TimeUnit.SECONDS);
            return flyway.migrate().migrationsExecuted;
        }).subscribeOn(Schedulers.boundedElastic())).collectList().block(Duration.ofSeconds(30));
        assertThat(migrations).containsExactlyInAnyOrder(1, 0);
        assertThat(jdbc.queryForList("SELECT id FROM roles ORDER BY id", String.class))
                .containsExactly("ROLE_ADMIN", "ROLE_EDITOR", "ROLE_USER");
        assertThat(jdbc.queryForObject("SELECT name FROM roles WHERE id = 'ROLE_ADMIN'", String.class))
                .isEqualTo("Custom administrator");
    }

    @Test
    void concurrentPurgeSkipsLockedRowsAndCommitsAtMostOneBatchPerCall() throws Exception {
        for (int i = 0; i < 10; i++) {
            jdbc.update("INSERT INTO token_registry (jti, expires_at) VALUES (?, clock_timestamp() - INTERVAL '1' HOUR)", "old-" + i);
        }
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT jti FROM token_registry WHERE jti = 'old-0' FOR UPDATE");
                 var row = lock.executeQuery()) {
                assertThat(row.next()).isTrue();
            }
            var results = Flux.range(0, 2).flatMap(i -> registry.purgeExpired(Duration.ZERO)).collectList().block();
            assertThat(results).hasSize(2).allSatisfy(deleted -> assertThat(deleted).isBetween(0, 2));
            int deleted = results.stream().mapToInt(Integer::intValue).sum();
            assertThat(deleted).isPositive();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM token_registry WHERE expires_at IS NOT NULL", Integer.class))
                    .isEqualTo(10 - deleted);
            // Проверяем весь плановый цикл, включая границу последнего неполного пакета.
            var tokenProperties = new TokenProperties(null, null, null, null, Duration.ZERO, null);
            new TokenRegistryMaintenance(registry, tokenProperties).purge().block(Duration.ofSeconds(5));
            assertThat(jdbc.queryForList("SELECT jti FROM token_registry ORDER BY jti", String.class))
                    .containsExactly("legacy", "old-0");
            connection.rollback();
        }
        assertThat(registry.purgeExpired(Duration.ZERO).block()).isOne();
    }

    @Test
    void contestedTokenWritesTimeOutWithoutConsumingTheToken() throws Exception {
        registry.register("prior", expiration).block();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT jti FROM token_registry WHERE jti = 'prior' FOR UPDATE");
                 var row = lock.executeQuery()) {
                assertThat(row.next()).isTrue();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO token_registry (jti, expires_at) VALUES ('new-registration', clock_timestamp() + INTERVAL '1' HOUR)
                    """)) {
                insert.executeUpdate();
            }
            var registration = registry.register("new-registration", expiration).toFuture();
            var exchange = registry.exchange("prior", "child", expiration).toFuture();
            var logout = registry.revoke("prior").toFuture();
            assertThatThrownBy(() -> registration.get(4, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> exchange.get(4, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> logout.get(4, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(DataAccessException.class);
            connection.rollback();
        }
        assertThat(registry.isActive("prior").block()).isTrue();
        assertThat(registry.isActive("child").block()).isFalse();
        registry.register("new-registration", expiration).block();
        assertThat(registry.exchange("prior", "child", expiration).block()).isTrue();
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
