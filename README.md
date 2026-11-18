# identity-service — сервис аутентификации и профиля пользователя (SOA, портал ЕТД)

Перенос ответственностей монолита fk-portal: аутентификация по SSO-заголовку, сбор профиля и ролей
из ОШС (SOAP) / ЮСБ (REST), локальные администраторы, выпуск JWT (JWKS) для portal-edge и других сервисов.
Проектные документы — в [docs/](docs/README.md) (ADR-01, ADR-02, объём выноса и статус реализации).

## Стек

- Java 25, Gradle Kotlin DSL, multi-module
- Spring Boot 4.1.2, Spring Cloud 2025.1.2, Spring WebFlux (Netty), Jackson 3
- etcd (jetcd) + config-source (read-only + watch + локальный кэш)
- Resilience4j (через command-support), OpenTelemetry, Lombok
- PostgreSQL (локальные пользователи, роли, реестр отозванных токенов)

## Модули

| Модуль | Назначение |
|---|---|
| identity-app | Boot-приложение: контроллеры, внутренний API, JWT-эндпоинты, БД |
| profile-support | Ядро профиля: OssConnection, бэкенды ОШС/ЮСБ, команды, ProfileService, кэш, конфиги из etcd |
| token-support | Минт/проверка JWT, JWKS |
| etcd-client, config-source, command-support, soap-client, soap-wsdl-resolver, webclient-support | скопированы из soa/nav-composer (позже — через артифактори) |

## Требования к окружению

- JDK 25 (локально: `C:\Users\AlexFlanker\.jdks\azul-25.0.5.0.3`)
- **Путь проекта должен быть ASCII**: на Windows лаунчер JDK читает `@argfile` Gradle в cp1251,
  а Gradle пишет его в UTF-8 — кириллица в пути ломает тест-воркеры
  (`Could not find or load main class worker.org.gradle...GradleWorkerMain`).
  Проект специально размещён в `C:\dev\identity-service`.
- Gradle user home проекта — `C:\dev\identity-service\.gradle-home` (см. ниже, не обязателен для IDE)

## Сборка и тесты

```powershell
$env:JAVA_HOME='C:\Users\AlexFlanker\.jdks\azul-25.0.5.0.3'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME='C:\dev\identity-service\.gradle-home'
.\gradlew.bat build              # полная сборка с тестами
.\gradlew.bat :identity-app:test # тесты одного модуля
```

Не задавайте `GRADLE_RO_DEP_CACHE`: с ним Gradle не генерирует `workerMain/gradle-worker.jar`
в локальном кэше, и тесты падают с ClassNotFoundException.

## Запуск

```powershell
java -jar identity-app\build\libs\identity-app-0.0.1-SNAPSHOT.jar
```

Необходимы: etcd (конфиги сервиса) и PostgreSQL (пользователи/роли/токены, см. `spring.datasource.*` в application.yml).
Схема БД мигрируется **Flyway** при старте (`identity-app/src/main/resources/db/migration`, бин — `FlywayConfiguration`:
в Spring Boot 4 автоконфигурации Flyway нет). Для БД, где таблицы уже существовали, включён `baselineOnMigrate`:
такая БД помечается как «V1 применена» без выполнения `V1__init.sql`, поэтому изменения схемы оформляются
только новыми миграциями (`V2__…`).

## Конфигурация

Из application.yml (секреты в проде — через secret-manager):

| Свойство | Назначение |
|---|---|
| `server.port` | Порт (по умолчанию 8091) |
| `identity.internal.api-key` | Общий ключ внутреннего API (заголовок `X-Internal-Key`) |
| `identity.token.ttl` / `issuer` / `key-id` | TTL access-токена, iss (URL-форма), kid ключа подписи |
| `admin.security.usb.*` | `applicationCode`, `resourceTypeCode`, `operationCode` для USS |
| `spring.datasource.*` | Подключение к PostgreSQL |
| `etcd.*`, `config.source.*` | Клиент etcd и кэширование конфигов |

Из etcd (префикс `fk-portal/`, hot-reload через config-source): `OSSConfig`, `UsbConfig` —
DTO содержат только необходимые сервису поля.

## API

| Метод/путь | Назначение |
|---|---|
| `POST /api/v1/token` | Выпуск JWT: по SSO-заголовку (`OSSConfig.headerName`, cookie-fallback, суффикс `^M`) или `{username,password}` локального админа |
| `POST /api/internal/token/exchange` | Перевыпуск токена с перечитыванием ролей (`Authorization: Bearer <jwt>`) |
| `POST /api/v1/logout` | Отзыв токена (jti в реестр) |
| `GET /.well-known/jwks.json` | Публичные ключи подписи (JWKS) |
| `POST /api/internal/v1/users/{login}/profile` | Профиль + роли + организации (внутренний API, заголовок `X-Internal-Key`) |
| `POST /api/internal/v1/users/{login}/groups` | Роли пользователя (внутренний API) |
| `POST /api/internal/v1/users/{login}/custom-attributes` | Редактирование кастомных полей (внутренний API, ОШС) |
| `GET /roles`, `GET /roles/{id}` | Локальные роли |

Claims JWT: `sub`, `username`, `roles[]`, `org_guid`, `org_code`, `auth_source`, `iss`, `jti`, `iat`, `exp`.
Подпись: RS256; ключ генерируется при старте (kid `identity-1`), загрузка из PEM/secret-manager — харденинг.

## Мониторинг

Actuator на том же порту (`management.endpoints.web.exposure.include` в application.yml):
`/actuator/health`, `/actuator/info`, `/actuator/metrics`, `/actuator/env`, `/actuator/prometheus`.

Скрейп Prometheus — `metrics_path: /actuator/prometheus`, порт сервиса (8091).
Эндпоинт появляется только при наличии `io.micrometer:micrometer-registry-prometheus` в зависимостях
(в `identity-app/build.gradle.kts`); экспорт метрик по OTLP выключен (`management.otlp.metrics.export.enabled=false`).

## Открытые пункты (харденинг)

- Загрузка/ротация ключей подписи из PEM/secret-manager;
- мTLS для внутреннего API вместо общего ключа;
- Rate limiting на `/api/v1/token`;
- Прогон в dev-контуре против реальных ОШС/ЮСБ;
- Интеграция с fk-portal через feature-toggle — следующий шаг, вне текущего плана.
