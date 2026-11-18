# identity-service — сервис аутентификации и профиля пользователя (SOA, портал ЕТД)

Перенос ответственностей монолита fk-portal: аутентификация по SSO-заголовку, сбор профиля и ролей
из ОШС (SOAP) / ЮСБ (REST), локальные администраторы, выпуск JWT (JWKS) для portal-edge и других сервисов.
Для запуска см. [настройки приложения](docs/configuration.md). Для интеграции см. [функции сервиса и пример бизнес-процесса](docs/functions.md). Архитектурные решения и статус миграции собраны в [индексе документации](docs/README.md).

## Стек

- Java 25, Gradle Kotlin DSL, multi-module
- Spring Boot 4.1.2, Spring Cloud 2025.1.2, Spring WebFlux (Netty), Jackson 3
- etcd (jetcd) + config-source (read-only + watch + локальный кэш)
- Resilience4j (через command-support), OpenTelemetry, Lombok
- PostgreSQL (локальные пользователи, роли, реестр выданных и отозванных JWT)

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

Для проверки реестра на PostgreSQL задайте `IDENTITY_TEST_POSTGRES_URL` (JDBC URL отдельной тестовой базы), `IDENTITY_TEST_POSTGRES_USER` и `IDENTITY_TEST_POSTGRES_PASSWORD`. При запуске `:identity-app:test` тесты создают и удаляют собственные схемы. Тестовой роли требуется право `CREATE` на базу. Обычный прогон проверяет реестр на H2.

Не задавайте `GRADLE_RO_DEP_CACHE`: с ним Gradle не генерирует `workerMain/gradle-worker.jar`
в локальном кэше, и тесты падают с ClassNotFoundException.

## Запуск в Docker (docker compose)

Сервис входит в compose-стенд портала: `C:\Users\AlexFlanker\Documents\ОТР\solutions\fk-portal\docker-compose.yml`
(сервис `identity-service` + `jaeger`). etcd и PostgreSQL уже работают в docker на хосте — контейнер обращается к ним
через host-gateway (профиль `docker`, см. [application-docker.yml](identity-app/src/main/resources/application-docker.yml)).

```powershell
# в C:\Users\AlexFlanker\Documents\ОТР\solutions\fk-portal
docker compose build identity-service        # предварительно: gradlew build в C:\dev\identity-service
docker compose up -d jaeger
docker compose up -d --no-deps identity-service
```

- Профиль `docker`: etcd `http://etcd-client:2379` (host-gateway), БД `jdbc:postgresql://host.docker.internal:5432/identity`
  (роль/БД создаются при первичной инициализации postgres — `environment/docker/pg/init/02_identity-service.sql`),
  ключ подписи ephemeral, трассы — все в Jaeger (`management.tracing.sampling.probability=1.0`).
- `IDENTITY_INTERNAL_API_KEY` в compose должен совпадать с `IDENTITY_INTERNAL_API_KEY` монолита
  (`admin.identity-service.api-key`).
- Jaeger UI: `http://localhost:16686`, OTLP gRPC — порт 4317.

## Запуск

```powershell
java -jar identity-app\build\libs\identity-app-0.0.1-SNAPSHOT.jar
```

Перед запуском укажите путь к ключу подписи в настройках Spring Boot. Пример приведён в разделе «Ключи и ротация». Для изолированной локальной разработки можно явно включить `identity.token.keys.ephemeral=true`: при каждом таком запуске потребуется новый вход.

Необходимы etcd (конфиги сервиса) и PostgreSQL (пользователи/роли/токены, см. `spring.datasource.*` в application.yml).
При старте **Flyway** применяет [миграции](identity-app/src/main/resources/db/migration). Для существующей непустой схемы без истории миграций сервис включает `baselineOnMigrate(true)` в [конфигурации Flyway](identity-app/src/main/java/com/otr/portal/identity/config/FlywayConfiguration.java).

## Конфигурация

Из application.yml (секреты в проде — через secret-manager):

| Свойство | Назначение |
|---|---|
| `server.port` | Порт (по умолчанию 8091) |
| `identity.internal.api-key` | Секрет для `X-Internal-Key`, обязателен для доступа ко всем внутренним методам, включая exchange |
| `identity.token.ttl` / `max-session-lifetime` | Время жизни одного JWT (30 мин) и предел цепочки обменов от первичной выдачи (8 ч) |
| `identity.token.issuer` / `audiences` | Точный URL издателя и список получателей (по умолчанию `https://identity-service` и `[portal-edge]`) |
| `identity.token.clock-skew` / `jwks-cache-ttl` | Допуск на расхождение часов (30 с) и срок HTTP-кэширования JWKS (5 мин) |
| `identity.token.registry.transaction-timeout` | Тайм-аут транзакции реестра после получения JDBC-соединения, включая ожидание блокировки. По умолчанию 5 с, задаётся целым числом секунд |
| `identity.token.registry.purge-batch-size` | Максимум строк за одну транзакцию очистки, от 1 до 10 000. По умолчанию 500 |
| `identity.token.registry.purge-delay` / `purge-initial-delay` | Интервал от окончания очистки до следующего запуска (1 ч) и задержка первого запуска (1 мин) |
| `identity.profile.cache.maximum-size` | Максимум кэшированных профилей на реплику, по умолчанию 10 000 |
| `identity.auth.local-password-enabled` / `sso-cookie-enabled` | Дополнительные способы входа, оба по умолчанию выключены |
| `admin.security.usb.*` | `applicationCode`, `resourceTypeCode`, `operationCode` для USS |
| `spring.datasource.*` | Подключение к PostgreSQL |
| `etcd.*`, `config.source.*` | Клиент etcd и кэширование конфигов |

Конфигурации `OSSConfig` и `UsbConfig` читаются из etcd с префиксом `fk-portal/`.
Параметр `OSSConfig.cacheTimeMin` ограничивает возраст профиля от момента записи. Изменение конфигурации ОШС или ЮСБ и восстановление watch в etcd сбрасывают локальный кэш профилей и бэкендов. Новые команды используют актуальные тайм-ауты и лимиты конкурентности. Начатые вызовы завершаются со своими настройками.

### Проверка готовности и метрики

Для приёма трафика используйте `/actuator/health/readiness`. Проверка учитывает состояние приложения, соединение с БД и конфигурацию SSO. Для ЮСБ требуются обе записи, `OSSConfig` и `UsbConfig`. После исправления записей сервис восстанавливает готовность при следующей успешной проверке. Для проверки самого процесса используйте `/actuator/health/liveness`.

Prometheus получает метрики по `/actuator/prometheus`. Чтобы включить экспорт трасс, задайте `management.tracing.export.otlp.enabled=true` и адрес gRPC-коллектора в `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`. Доля выборки задаётся `management.tracing.sampling.probability` и по умолчанию равна `0.1`.

## API

| Метод/путь | Назначение |
|---|---|
| `POST /api/v1/token` | Выпуск JWT по доверенному SSO-заголовку (`OSSConfig.headerName`, обработка суффикса `^M`). Опционально — вход по `{username,password}` или через SSO-cookie |
| `POST /api/internal/token/exchange` | Однократный обмен действующего токена с перечитыванием прав (`Authorization: Bearer <jwt>` и `X-Internal-Key`) |
| `POST /api/v1/logout` | Отзыв предъявленного Bearer JWT по его `jti`; успешный ответ HTTP 204 |
| `GET /.well-known/jwks.json` | Публичные ключи подписи (JWKS) |
| `POST /api/internal/v1/users/profile` | Профиль + роли + организации; тело `{"login","bypassCache"}`, ответ содержит признак `cacheHit` (внутренний API, заголовок `X-Internal-Key`) |
| `POST /api/internal/v1/users/groups` | Роли пользователя; тело `{"login"}` (внутренний API) |
| `POST /api/internal/v1/users/organizations` | Организации пользователя из профиля; тело `{"login"}` (внутренний API) |
| `POST /api/internal/v1/users/custom-attributes` | Редактирование кастомных полей; тело `{"login","attributes"}` (внутренний API, ОШС) |
| `POST /api/internal/v1/users/cache/invalidate` | Полная инвалидация кэша профилей; тело не требуется (внутренний API) |
| `GET /roles`, `GET /roles/{id}` | Локальные роли |

### Жизненный цикл JWT

В проде доступ к выдаче токенов должен идти через доверенный SSO-прокси по TLS. Прокси аутентифицирует пользователя, удаляет одноимённый клиентский заголовок и сам устанавливает канонический логин с тем же регистром, что в ОШС/ЮСБ. Для первичной выдачи идентификатор должен происходить из запроса, аутентифицированного внешним SSO. Сервис ищет профиль по этому идентификатору и запрашивает актуальные права при каждом выпуске и обмене. Ограничьте прямой сетевой доступ к сервису. Включайте чтение SSO-cookie только за шлюзом, который проверяет и перезаписывает эту cookie. Для локального входа оператор создаёт учётную запись с BCrypt-паролем и ролями. Перед включением локального входа на существующей БД проверьте ранее созданные демонстрационные учётные записи.

1. При первичной выдаче сервис регистрирует `jti` и `exp` в PostgreSQL и возвращает `accessToken`, `tokenType` и `expiresInSeconds`. Во внутреннем формате портала заголовок JWT содержит `alg=RS256`, `typ=at+jwt` и `kid`. Значения `sub` и `username` совпадают с каноническим логином. Также обязательны `iss`, `aud`, `roles[]`, `auth_source`, `jti`, `auth_time`, `iat` и `exp`. Если определена организация, токен может содержать `org_guid` и `org_code`. Значение `auth_time` задаётся в момент первичной выдачи JWT порталом.
2. Потребитель получает открытые ключи из `/.well-known/jwks.json` по заранее настроенному HTTPS-адресу. Он проверяет алгоритм, тип, подпись по `kid`, ожидаемый `iss`, собственный идентификатор в `aud` и срок действия. Для будущего portal-edge контрактный тест должен охватывать получение JWKS по HTTP, смену `kid` и отказ при неверной аудитории.
3. При обмене сервис перечитывает права пользователя. В одной транзакции он погашает зарегистрированный действующий `jti` и регистрирует новый. Ответ с новым токеном отправляется после фиксации транзакции. Обмен сохраняет `auth_time`, поэтому последний JWT в цепочке может иметь сокращённый TTL. По достижении абсолютного предела нужна первичная выдача через SSO. Если ответ успешного обмена потерян, повторная попытка с прежним JWT вернёт HTTP 401.
4. Для выхода передайте JWT в заголовке `Authorization: Bearer <jwt>` на `POST /api/v1/logout`. Успешный запрос и повторный запрос с тем же действительным JWT возвращают HTTP 204. Отзыв касается только предъявленного `jti`. Если обмен успел завершиться первым, выданный им JWT сохраняет право на обмен. Если logout завершился первым, обмен предъявленного JWT вернёт HTTP 401. Сессией SSO управляет внешний провайдер. При автономной проверке подписи потребитель может принимать ранее выданный токен до `exp` с учётом допуска часов.

Миграция V3 переводит реестр со списка отзывов на регистрацию каждого выданного JWT. Перед переключением прекратите обработку выпуска, обмена и logout на старых репликах и дождитесь завершения их запросов: прежняя версия трактует каждую строку как отзыв. Для обмена JWT, выданного до регистрации, потребуется первичная выдача. Существующие записи об отзыве сохраняются.

### Ключи и ротация

Смонтируйте приватный RSA-ключ в формате PKCS#8 PEM (`BEGIN PRIVATE KEY`) из secret-manager как файл только для чтения, доступный процессу сервиса. Открытые ключи для периода ротации имеют формат SubjectPublicKeyInfo PEM (`BEGIN PUBLIC KEY`). Поддерживаются RSA-ключи от 2048 бит. Для нового ключа можно использовать 3072 бита. Все реплики получают одинаковую конфигурацию из доверенного источника. Пример настроек для двух поколений ключей:

```yaml
identity:
  token:
    issuer: https://identity.example.org
    audiences: [portal-edge]
    key-id: signing-2026-11
    keys:
      private-key: /run/secrets/identity/signing-2026-11.pem
      verification-keys:
        signing-2027-01: /run/secrets/identity/signing-2027-01.pub.pem
```

Ключи загружаются при старте. Для плановой ротации выполните три последовательных обновления реплик:

1. Создайте новую пару с новым `kid`. Добавьте её публичный ключ в `verification-keys` на всех репликах. Проверьте, что обе записи доступны в JWKS. Дождитесь обновления кэшей потребителей.
2. Смените `key-id` и `private-key` на новые, в `verification-keys` оставьте старый публичный ключ. Во время обновления обе группы реплик принимают оба поколения.
3. После остановки последней реплики со старым приватным ключом выждите максимальный оставшийся TTL выданных ею токенов плюс допуск часов. Уберите старый публичный ключ и обновите реплики.

При компрометации исключите скомпрометированный ключ из всех верификаторов, переключите подпись и сбросьте JWKS-кэши потребителей. Пользователям с JWT, подписанным этим ключом, потребуется новая выдача. Токены, ранее перевыпущенные под другим доверенным ключом, сохраняют собственный срок действия. Сервис проверяет точное множество аудиторий при обмене. Изменение этого множества или уменьшение допустимых сроков может потребовать нового входа. При переходе с первой итерации потребуется первичная выдача JWT через SSO, поскольку новый валидатор требует `at+jwt`, `aud` и `auth_time`. Согласуйте переключение версии с потребителями.

## Мониторинг

Actuator на том же порту (`management.endpoints.web.exposure.include` в application.yml):
`/actuator/health`, `/actuator/info`, `/actuator/metrics`, `/actuator/env`, `/actuator/prometheus`.

Скрейп Prometheus — `metrics_path: /actuator/prometheus`, порт сервиса (8091).
Эндпоинт появляется только при наличии `io.micrometer:micrometer-registry-prometheus` в зависимостях
(в `identity-app/build.gradle.kts`); экспорт метрик по OTLP выключен (`management.otlp.metrics.export.enabled=false`).

## Открытые пункты (харденинг)

- мTLS для внутреннего API вместо общего ключа;
- Rate limiting на `/api/v1/token`;
- Прогон в dev-контуре против реальных ОШС/ЮСБ и включение переключателя в fk-portal на стендах (реализация интеграции — см. [docs/identity-service.md](docs/identity-service.md), раздел «Интеграция с fk-portal через feature-toggle»).
