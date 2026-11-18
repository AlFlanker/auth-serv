# Настройка identity-service

Для старта нужны JDK 25, PostgreSQL и ключ подписи JWT. Доступ к конфигурации в etcd нужен для готовности сервиса и входа по SSO. Проверка соединения с etcd при старте по умолчанию выключена. Для выдачи по SSO также нужен работающий источник профилей — ОШС (СОБИ SOAP) или ЮСБ (USS REST). При сборке из исходников Gradle Wrapper 9.6.0 загружает SOAP-контракт `ru.otr.ext:fk-services-sobi:2.0.2` из Nexus ОТР.

| Что настроить | Где задать | Когда меняется |
|---|---|---|
| Порт, JDBC, адреса и учётные данные etcd, внутренний ключ API, JWT, параметры USS и наблюдаемости | Внешний файл Spring Boot, переменные окружения или аргументы запуска. Исходные значения заданы в [application.yml](../identity-app/src/main/resources/application.yml) и классах настроек | При запуске процесса |
| Закрытый RSA-ключ и открытые ключи ротации | PEM-файлы из хранилища секретов. Пути и `kid` задаются в Spring | При запуске каждой реплики |
| Источник профилей, адрес, SSO-заголовок, группы, тайм-ауты | JSON под ключами `fk-portal/OSSConfig` и для ЮСБ также `fk-portal/UsbConfig` в etcd. Регистр имён важен | Сервис читает изменения через watch и обновляет кэши |

## 1. Подготовьте PostgreSQL и ключи

Создайте базу и роль с правом подключения и создания таблиц и индексов в схеме сервиса. По умолчанию приложение использует `jdbc:postgresql://localhost:5432/identity`, локальные имя пользователя и пароль `identity`, до 10 соединений Hikari. Для своего контура задайте `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` и `SPRING_DATASOURCE_PASSWORD` через настройки процесса.

При старте сервис сам запускает Flyway. V1 создаёт таблицы пользователей, полномочий, ролей и реестра токенов. V2 и V3 добавляют срок действия и учёт выданных JWT. V4 добавляет недостающие встроенные роли. Перед первым запуском на непустой схеме без истории Flyway проверьте, что её структура соответствует V1. Сервис включает `baselineOnMigrate(true)`. Тестовые пользователи автоматически не создаются. [Миграции](../identity-app/src/main/resources/db/migration)

Для стабильной подписи укажите `identity.token.keys.private-key` — путь к RSA PKCS#8 PEM (`BEGIN PRIVATE KEY`, от 2048 бит). Все реплики должны получать одно и то же активное поколение ключей. Только для изолированного локального запуска можно включить `identity.token.keys.ephemeral=true` без PEM-файлов. После перезапуска такой ключ сменится. Для всех маршрутов `/api/internal/**` задайте общий секрет в `IDENTITY_INTERNAL_API_KEY`. Пустой или неверный ключ даёт HTTP 401. Обмен токена дополнительно требует действующий Bearer JWT. [Порядок ротации](../README.md#ключи-и-ротация)

### Постоянный ключ для локального запуска на Windows

В PowerShell 7.1+ с установленным OpenSSL создайте RSA-ключ один раз в каталоге вашего пользователя вне репозитория. В примере `$kid` задаёт публичный идентификатор поколения ключа. Команда `New-Item` для PEM остановит выполнение при существующем файле.

```powershell
$openssl = (Get-Command openssl -CommandType Application -ErrorAction Stop).Source
$kid = 'dev-signing-1'
$keyDir = Join-Path $env:LOCALAPPDATA 'identity-service\keys'
New-Item -ItemType Directory -Path $keyDir -Force | Out-Null
$pem = Join-Path $keyDir "$kid.pem"
if (New-Item -ItemType File -Path $pem -ErrorAction Stop) {
    & $openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -outform PEM -out $pem
    if ($LASTEXITCODE -ne 0) {
        Remove-Item -LiteralPath $pem
        throw 'Не удалось создать RSA-ключ'
    }
}
```

Результат — незашифрованный PKCS#8 PEM (`BEGIN PRIVATE KEY`). Ограничьте права чтения файла учётной записью процесса. Сервис сам строит открытую часть активного ключа и публикует её в JWKS. Для следующих запусков используйте тот же файл.

## 2. Выберите источник профилей в etcd

Перед записью замените адреса `example.org` на адреса вашего контура и согласуйте `headerName` с SSO-прокси. Пользователя аутентифицирует внешний SSO-провайдер. Доверенный прокси проверяет результат, удаляет одноимённый клиентский заголовок и передаёт канонический логин с тем же регистром, что в источнике. Прямой доступ к маршруту первичной выдачи ограничьте доверенным прокси.

**ОШС.** Сохраните следующий пример в `OSSConfig.json`. Для запросов изменения атрибутов передайте согласованное с СОБИ значение `ossSecureKey` через защищённую процедуру обновления записи.

```json
{
  "ossType": "SOBI_WEB_SERVICE",
  "ossUrl": "https://sobi.example.org/services/OrganizationService?wsdl",
  "headerName": "X-SSO-Login",
  "ossGroupEnabled": true,
  "cacheTimeMin": 60,
  "userDetailsTimeout": 1,
  "authoritiesTimeout": 1,
  "manageUserCustomAttributesTimeout": 5
}
```

**ЮСБ.** Для этого варианта используйте следующую запись `OSSConfig.json`. Адрес `ossUrl` указывает на базовый URL REST-сервиса.

```json
{
  "ossType": "USB",
  "ossUrl": "https://uss.example.org",
  "headerName": "X-SSO-Login"
}
```

Для ЮСБ создайте также `UsbConfig.json`. Пример явно включает контракт V2 и загрузку ролей.

```json
{
  "apiVersion": "V2",
  "usbGroupEnabled": true,
  "userInfoPath": "/identification/v2/employee/findByJwt",
  "userGroupPath": "/authorization/v2/access/resources"
}
```

Пример записи в выбранный dev-кластер из PowerShell. `etcdctl put` заменяет значение ключа целиком. Для уже настроенного контура переносите необходимые поля из действующей конфигурации.

```powershell
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:ETCDCTL_ENDPOINTS = 'http://localhost:2379'
# Только для ЮСБ, перед переключением OSSConfig
Get-Content -Raw -Encoding utf8 .\UsbConfig.json | etcdctl put fk-portal/UsbConfig
# Для обоих вариантов
Get-Content -Raw -Encoding utf8 .\OSSConfig.json | etcdctl put fk-portal/OSSConfig
```

| Настройка | По умолчанию и единицы |
|---|---|
| `OSSConfig.ossType`, `ossUrl`, `headerName` | Тип по умолчанию `SOBI_WEB_SERVICE`. Нужны абсолютный HTTP(S) URL и допустимое имя SSO-заголовка. Имя `password` зарезервировано. Переменная процесса с точным именем `oss.url` переопределяет `ossUrl`. `SOBI_REST` — заглушка |
| `userDetailsTimeout`, `authoritiesTimeout`, `manageUserCustomAttributesTimeout` в OSSConfig | 1, 1 и 5 секунд соответственно. Соответствующие поля `userDetailsMaxConcurrentRequests`, `authoritiesMaxConcurrentRequests`, `manageUserCustomAttributesMaxConcurrentRequests` задают по 20 одновременных вызовов на команду на реплике |
| Группы и версия USS | `ossGroupEnabled=true` для ОШС. Для ЮСБ `usbGroupEnabled=false`, `apiVersion=V1`. Пути по умолчанию совпадают с показанными для V2. Для идентификации V1 отправляет GET с `jwt_token`, V2 — POST с JSON. Для USS в Spring заданы `admin.security.usb.application-code=ETD_PORTAL`, `resource-type-code=ETD_ROLE`, `operation-code=ACCESS` под тем же префиксом |
| Профиль | `OSSConfig.cacheTimeMin=60` минут от записи. `identity.profile.cache.maximum-size=10000` записей на реплику. Выдача и обмен JWT заново запрашивают профиль SSO |
| Чтение конфигурации | `etcd.endpoints[0]=http://localhost:2379`, `config.source.etcd.key-prefix=fk-portal/`, `timeout=10s`, `watch-require-leader=true` под тем же префиксом. Для RBAC задайте `etcd.user` и `etcd.password` |
| Кэш конфигурации | Под `config.source.cache` заданы `enabled=true`, `maximum-size=10000`, `expire-after-write=10m`. Пустой `watch-prefix` охватывает весь настроенный префикс сервиса. Задержка повторного подключения растёт от `watch-retry-backoff=5s` до `watch-retry-max-backoff=1m` |

HTTP-клиент профилей в текущей реализации допускает до 200 соединений на пул удалённого адреса, на установку соединения отводит 10 секунд, на ожидание данных ответа — 60 секунд. Тайм-аут команды из `OSSConfig` ограничивает весь вызов, поэтому он может завершиться раньше.

При смене источника или восстановлении watch сервис сбрасывает производные кэши. Изменённые тайм-ауты и пределы конкурентности применяются к новым вызовам. Если задать `etcd.startup.verify-connection=true`, сервис проверит список членов кластера. По умолчанию тайм-аут попытки 5 секунд, до трёх повторов с задержкой от 5 до 10 секунд.

## 3. Запустите приложение

Команды рассчитаны на PowerShell 7.1+ и выполняются из корня проекта в каталоге с ASCII-путём. Предполагается, что JDK 25 доступен через `JAVA_HOME` и `PATH`, PostgreSQL и etcd запущены, а роль БД и её пароль уже созданы. Введите пароль этой роли и общий ключ dev-контура из хранилища секретов в скрытых приглашениях. Тем же внутренним ключом затем воспользуется доверенный клиент.

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/identity'
$env:SPRING_DATASOURCE_USERNAME = 'identity'
$env:SPRING_DATASOURCE_PASSWORD = Read-Host 'Пароль dev-роли PostgreSQL' -MaskInput
$env:IDENTITY_INTERNAL_API_KEY = Read-Host 'Общий ключ внутреннего API dev-контура' -MaskInput
$kid = 'dev-signing-1'
$pem = (Resolve-Path (Join-Path $env:LOCALAPPDATA "identity-service\keys\$kid.pem") -ErrorAction Stop).Path
.\gradlew.bat :identity-app:bootJar
if ($LASTEXITCODE -ne 0) { throw 'Сборка не удалась' }
java -jar .\identity-app\build\libs\identity-app-0.0.1-SNAPSHOT.jar "--identity.token.keys.private-key=$pem" "--identity.token.key-id=$kid"
```

| Параметры JWT | Значения по умолчанию |
|---|---|
| `identity.token.issuer`, `audiences`, `key-id` | `https://identity-service`, `[portal-edge]`, `identity-1`. Для своего контура согласуйте issuer и аудитории с потребителями |
| `identity.token.ttl`, `max-session-lifetime`, `clock-skew`, `jwks-cache-ttl` | `30m` на один JWT, `8h` от первичной выдачи на цепочку обменов, `30s` допуск часов, `5m` время HTTP-кэширования JWKS |
| `identity.token.registry.*` | `transaction-timeout=5s` после получения JDBC-соединения, положительное целое число секунд. `purge-batch-size=500` строк за транзакцию (1–10 000). Первая очистка через `purge-initial-delay=1m`, следующие — через `purge-delay=1h` после завершения |
| `identity.auth.local-password-enabled`, `identity.auth.sso-cookie-enabled` | Оба выключены. Основной вход — через SSO-заголовок доверенного прокси |

## 4. Проверьте работающий сервис

В другом терминале PowerShell выполните проверки на порту `server.port` (по умолчанию 8091).

```powershell
$base = 'http://localhost:8091'
(Invoke-RestMethod "$base/actuator/health/liveness").status
(Invoke-RestMethod "$base/actuator/health/readiness").status
(Invoke-RestMethod "$base/.well-known/jwks.json").keys | Select-Object kid,kty,alg
(Invoke-RestMethod "$base/roles").id
```

Обе проверки здоровья должны вернуть `UP`. Готовность учитывает состояние приложения, соединение с БД и настройки SSO. При `USB` требуются обе записи etcd. Проверьте внутренний маршрут и общий ключ запросом профиля существующего тестового пользователя. Сервис может вернуть профиль из кэша. Введите тот же ключ внутреннего API в терминале клиента.

```powershell
$login = (Read-Host 'Канонический логин тестового пользователя')
$internalHeaders = @{ 'X-Internal-Key' = (Read-Host 'Общий ключ внутреннего API dev-контура' -MaskInput) }
$body = @{ login = $login; bypassCache = $false } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/api/internal/v1/users/profile" -Headers $internalHeaders -ContentType 'application/json' -Body $body | Select-Object login, cacheHit
```

Ответ должен содержать запрошенный логин. Чтобы проверить подключение к ОШС или ЮСБ, войдите тестовым пользователем через SSO-провайдера и вызовите через доверенный прокси `POST /api/v1/token`. При выдаче сервис читает свежий профиль в обход кэша профилей. Для проверки обмена однократно предъявите полученный JWT на `POST /api/internal/token/exchange` с `Authorization: Bearer <jwt>` и `X-Internal-Key`, затем предъявите новый Bearer JWT на `POST /api/v1/logout`. Успешный выход возвращает 204, последующий обмен этого JWT — 401.

Метрики доступны для Prometheus по `/actuator/prometheus`. Экспорт метрик по OTLP и экспорт трасс выключены. Для отправки трасс задайте `management.tracing.export.otlp.enabled=true` и `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`. По умолчанию транспорт gRPC, адрес `http://localhost:4317`, доля выборки `management.tracing.sampling.probability=0.1`.
