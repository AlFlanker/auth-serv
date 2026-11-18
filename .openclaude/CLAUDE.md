# identity-service — конвенции разработки

## Роль

Эксперт Java 25 / Spring Boot 4.1.x backend-разработчик для multi-module Gradle-проекта identity-service.

## Стек

- Java 25 (toolchain), Gradle Kotlin DSL, multi-module (`subprojects {}` в корне)
- Spring Boot **4.1.2** + Spring Cloud 2025.1.2, Spring WebFlux (Netty), **Jackson 3** (`tools.jackson`)
- etcd (jetcd) через скопированные модули `etcd-client`/`config-source`
- Resilience4j через `command-support`, OpenTelemetry, Lombok
- БД: PostgreSQL (prod) / H2 (тесты), доступ через `JdbcTemplate` + `Schedulers.boundedElastic()`

## Сборка и тесты (Windows)

```powershell
$env:JAVA_HOME='C:\Users\AlexFlanker\.jdks\azul-25.0.5.0.3'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME='C:\dev\identity-service\.gradle-home'
.\gradlew.bat build               # всё
.\gradlew.bat :<module>:test      # один модуль
```

- Путь проекта **только ASCII** (кириллица ломает `@argfile` тест-воркеров Gradle на Windows).
- `GRADLE_RO_DEP_CACHE` не задавать — блокирует генерацию `workerMain/gradle-worker.jar`.

## Конвенции

- Новый модуль → добавить в `settings.gradle.kts`; group/version/toolchain наследуются из корневого `subprojects {}`.
- Реактивный код: **никаких `.block()`** в контроллерах/сервисах; блокирующие вызовы (JDBC, BCrypt) —
  через `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`.
- Все исходящие вызовы к ОШС/ЮСБ — только командами `command-support` (Resilience4j: timeout/bulkhead/CB/retry).
- Конфиги из etcd: типизированные `ConfigKey<?>`-бины в `profile-support` (`WatchedConfigKeys`),
  DTO — только необходимые поля; изменение конфига → событие → сброс кэшей.
- `null` из `Mono.fromCallable` даёт **empty** Mono — используй `switchIfEmpty`/`justOrEmpty` там, где null важен.

## Особенности Spring Boot 4 / Security 7 / Jackson 3

- `spring.sql.init` и **FlywayAutoConfiguration** удалены — Flyway настраивается вручную
  (`FlywayConfiguration`, `baselineOnMigrate=true`); новая миграция = `V<n>__описание.sql`
  в `identity-app/src/main/resources/db/migration` (SQL должен быть совместим с H2 и PostgreSQL).
- `@AutoConfigureWebTestClient` удалён — в `@SpringBootTest` строй `WebTestClient.bindToApplicationContext(...)`.
- `JwsHeader` — в `org.springframework.security.oauth2.jwt` (не `...jose.jws`).
- Энкодеру JWT нужен **полный** JWKSet (с приватным ключом); наружу — только `publicJwkSet()`.
- Аннотаций `com.fasterxml.jackson.annotation` нет: wire-DTO — records + `JsonMapper` со `SNAKE_CASE`
  (образец — `UsbWireMapper`); `JsonInclude.Value` — из legacy `com.fasterxml.jackson.annotation`.
- `ResponseEntity.status(...).build()` — raw-тип; для generic-вывода используй `new ResponseEntity<>(status)`.

## Работа с документацией

- Проектные документы — в `docs/` (копии из portal-core; при расхождениях править обе копии).
- API и конфигурацию держать актуальными в корневом `README.md`.

## Запрещено

- Добавлять BOM-ы, не обновив корневой `build.gradle.kts`.
- Хардкодить секреты (api-key, ossSecureKey) — только конфиги/etcd, в проде secret-manager.
- Создавать ad-hoc скрипты вне `src/test`.
