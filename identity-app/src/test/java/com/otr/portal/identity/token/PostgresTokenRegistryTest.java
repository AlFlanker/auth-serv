package com.otr.portal.identity.token;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Properties;
import java.util.UUID;

/** Прогон контракта реестра на PostgreSQL с отдельной схемой для каждого теста. */
@EnabledIfEnvironmentVariable(named = "IDENTITY_TEST_POSTGRES_URL", matches = ".+")
class PostgresTokenRegistryTest extends TokenRegistryTest {
    private final String schema = "registry_test_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate admin;
    private boolean createdSchema;

    @Override
    DriverManagerDataSource createDataSource() {
        var source = new DriverManagerDataSource(System.getenv("IDENTITY_TEST_POSTGRES_URL"),
                System.getenv("IDENTITY_TEST_POSTGRES_USER"), System.getenv("IDENTITY_TEST_POSTGRES_PASSWORD"));
        admin = new JdbcTemplate(source);
        admin.execute("CREATE SCHEMA " + schema);
        createdSchema = true;
        var properties = new Properties();
        properties.setProperty("currentSchema", schema);
        source.setConnectionProperties(properties);
        if (!schema.equals(admin.queryForObject("SELECT current_schema()", String.class))) {
            throw new IllegalStateException("PostgreSQL test connection must use its isolated schema");
        }
        return source;
    }

    @AfterEach
    void dropTestSchema() {
        if (createdSchema) {
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Override
    String sessionIdSql() {
        return "SELECT pg_backend_pid()";
    }

    @Override
    String blockedSessionsSql() {
        return "SELECT COUNT(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))";
    }
}
