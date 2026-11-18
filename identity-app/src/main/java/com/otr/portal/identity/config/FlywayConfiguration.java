package com.otr.portal.identity.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Ручная конфигурация Flyway: в Spring Boot 4 автоконфигурация Flyway удалена,
 * поэтому бин создаём сами. Миграция выполняется при создании бина —
 * до сервисов, работающих с БД (они зависят от {@link Flyway} по конструктору).
 */
@Configuration(proxyBeanMethods = false)
public class FlywayConfiguration {

    @Bean
    Flyway flyway(DataSource dataSource) {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                // БД, где таблицы уже существовали до внедрения Flyway (например, dev-контуры):
                // базируемся на V1 без повторного выполнения
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();
        return flyway;
    }
}
