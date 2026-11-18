# identity-service: объём выноса из монолита

Статус: черновик — рабочий документ к ADR-02
Дата: 17.11.2026

Назначение: зафиксировать текущие ответственности монолита fk-portal, которые перейдут в сервис **identity-service** (Auth Service по ADR-01). Не входит в зону документа: portal-edge, nav-composer, config-center, portal-app.

## Границы

| Переходит в identity-service | Не переходит (куда) |
|---|---|
| Аутентификация: login, SSO; form-login админки — не прод, при переносе переводится на выпуск JWT | Верификация JWT на каждом запросе (portal-edge, по JWKS) |
| Выдача/продление JWT, logout | Локальные URL-правила `CheckRoutesFilter` (portal-edge) |
| Сбор профиля и ролей из ОШС/ЮСБ | Проверки доступности ресурсов СПИ/ЮСБ (nav-composer, `resource-access-support`) |
| Активная организация, локальные админы, сертификаты | Sticky-сессии, отдача UI (portal-edge / portal-app) |

Суть сервиса: «кто ты» — идентичность (JWT, профиль, роли), а не «что тебе доступно» и не «пускать ли по URL».

## Факты эксплуатации и следствия для объёма (17.11.2026)

- В проде используется **только** авторизация через HTTP-заголовок внешнего SSO-прокси (`RequestHeaderPreauthFilter`). JWT-фильтры монолита (`JWTLoginFilter`, `JWTAuthorizationFilter`), `TokenAuthFilter` (X-Token) и formLogin в реальной эксплуатации не используются.
- Локальные админы (`JdbcUserDetailsManager`, дефолт `user/123`) в проде используются; для ЮСБ-провайдера легитимно получение админских ролей из групп (`UsbOssRestBackend`, `usbGroupEnabled`).
- JWT интеграции nav-composer ↔ fk-portal (`NavComposerJwtFactory`) — отладочный механизм, а не прод-реализация; будет переработан после реализации identity-service.
- Следствие: legacy-JWT-механизмы **не переносим**, JWT реализуется в identity-service с нуля. Протокольные детали старого JWT (cookie `JWT-token`, заголовок `JWT-reauth`, sliding-продление, общий HMAC-секрет) не являются контрактами совместимости.

## Ответственности монолита, уходящие в identity-service

### 1. Аутентификация

Модуль `core/security`, пакеты authentication/cert/configuration:

- `RequestHeaderPreauthFilter` + `PreAuthenticatedAuthenticationProvider` — **единственный прод-механизм**: SSO-вход по заголовку внешнего прокси (в целевой схеме — portal-edge). Контракт SSO-заголовка переносится полностью:
  - имя заголовка — из конфига `OSSConfig.headerName`;
  - значение может приходить также в cookie с тем же именем (fallback `extractHeaderValueFromCookies`);
  - от значения отрезается суффикс `^M` (служебные учётки);
  - имя заголовка запоминается (`ExternalUserInfo`) и пробрасывается бэкендам — в целевой схеме это делает portal-edge из claims JWT (claim «источник аутентификации»);
- `JWTLoginFilter` — вход по логину/паролю (POST /login); в проде не используется, в identity-service реализуется заново (см. п. 2);
- локальные администраторы (`JdbcUserDetailsManager`, `ExtensionInMemoryUserDetailsManager`; дефолт `user/123`). formLogin админки в проде не используется; при переносе вход админа переводится на выпуск JWT (аналог `admin.security.auth-type.jwt`), а не на JSESSIONID — иначе сервис перестаёт быть stateless;
- `PortalHttpsChannelFilter`, клиентские сертификаты (`CertificateUtils`/`CertificateUtilsImpl`).

### 2. Выдача и жизненный цикл JWT

Реализуется с нуля, legacy-код не переносится (в проде JWT не использовался, см. «Факты эксплуатации»):

- минт JWT с асимметричной подписью, JWKS (общий HMAC-секрет и `alg.computeIfAbsent` не переносятся);
- продление/перевыпуск через token-exchange. **Не воспроизводить** sliding-продление `JWTAuthorizationFilter` (продление истёкшего токена без абсолютного лимита жизни и без перечитывания ролей). Обязательны: абсолютный срок жизни токена, перечитывание ролей при exchange, реестр выданных/отозванных токенов;
- разовый `AUTH_TOKEN` админа (`TokenAuthFilter`): выпуск и ротация в identity-service (вместо static-токена на JVM, печатаемого в лог); проверка — в portal-edge перед `/admin`;
- `JWTLogoutHandler`, `LogoutHandlerAdapter`, `UfosLogoutHandler` (в `UfosLogoutHandler` заменить `HttpProxy` на WebClient + Resilience4j).

### 3. Профиль пользователя (ядро сервиса)

Модуль `extensions/oss-support` (целиком, включая ЮСБ-профиль `UsbOssRestBackend`) + `soap-clients/soap.oss` (SOAP-клиент ОШС). Внимание: `extensions/usb-support` (`UsbService` — проверки доступов) сюда НЕ входит, он остаётся в контуре навигации:

- `OSSUserDetailsService` — фасад сбора профиля и ролей (в монолите помечен к рефакторингу; вынос — подходящий момент);
- `GetUserDetailsCommand`, `GetAuthoritiesCommand`, `OssCommandFactory`, `CommandResilienceDecorator` (Resilience4j);
- источники: ОШС `SobiOssWSBackend` (SOAP через `OssEndpointClient`) / `SobiOssRestBackend`; ЮСБ-профиль `UsbOssRestBackend` (перепаковка в формат ОШС). Админские роли из групп ЮСБ — легитимный прод-сценарий (`usbGroupEnabled`);
- модели: `OSSUser`, `OssOrganization`, пакет `ossuserinfo` из solutions/fk-portal (`EmployeeInfo`, `OrganizationInfo`, `SetActiveOrganizationRequest`, `OSSUserInfo`);
- `UserOrganizationState` — активная организация пользователя.

### 4. Роли и локальные пользователи

- `core/security`, пакет roles (`Role`);
- `core/user-role-storage`;
- `UserRoleLocalBackend` (solutions/fk-portal, authentication) — локальные роли/пользователи.

### 5. Logout и SSO-logout

Текущий `POST /api/v1/logout` завершает возможность обмена предъявленного `jti`. Отдельно для интеграции с монолитом рассматриваются следующие компоненты.

- `PoibLogoutUrlResolver`, `SsoLogoutUrlResolver` (solutions/fk-portal, authentication) — внешние logout-URL;
- `UserDescription`, `UserProfilePreferences` (solutions/fk-portal, authentication).

### 6. OAuth и прочий мёртвый код — НЕ переносится

- `extensions/oauth` — это OAuth 1.0a-клиент к Atlassian Jira, а не выдача токенов пользователям. Единственный потребитель — `jira-feign-client` (`JiraFeignClientFactory` → `OAuthService`). В сборку fk-portal `jira-feign-client` и `problem-report-storage-jira` не входят (в settings.gradle их нет); сам `oauth` в settings.gradle включён (строка 38), но ни один включённый модуль от него не зависит — мёртвый код. Интеграция с Jira удалена (коммит `ff6fe94f5`), код помечен устаревшим со сроком удаления 2026-09-17.
- `extensions/authentication-ad` (SPNEGO/AD) — в settings.gradle не включён, мёртвый код.

Вывод: модули в identity-service не мигрируют и являются кандидатами на удаление. Если в будущем понадобится OAuth/OIDC — реализация заново на новом стеке (Spring Security OAuth2).

### 7. Профильные контроллеры (внешний API не меняется)

- `OSSUserProfileController` (/user/current/profile), `UserDetailsController` (solutions/fk-portal);
- `UserLastLoginDateService` (solutions/fk-portal, metadata) — запись последнего входа (семантика изменится, см. п. 9).

### 8. Порт AuthenticationHelper

- Порт `AuthenticationHelper` (`core/otrportal-core`) остаётся в shared kernel и в каждом сервисе;
- реализация на базе `OSSUserDetailsService` (`AuthenticationCheckerSecurity`) уходит в identity-service; в остальных сервисах пользовательский контекст собирается из claims JWT.

### 9. Активная организация и смежные фильтры

Не вошло в предыдущие пункты, но относится к identity-контуру:

- `UserOrganizationState` (@SessionScope), cookie `ACTIVE_ORG` (SameSite=Strict, URL-encoded код СВР), `ActiveOrgResolverFilter`, `OrganizationGuardFilter` (HTTP 428, код `ORG_NOT_SELECTED`), `OrganizationContextManager` (локальный Caffeine-кэш);
- потребители активной организации за пределами identity (определяют решение открытого вопроса 2): portal-edge — `ActiveOrgHeaderEnricher` (заголовок «код СВР» бэкендам), portal-app — `ELKProblemReportEnricher` (тикеты) и `DocumentLinkService` (`/document-link`, org-GUID в запросах УФОС);
- семантика «последнего входа»: в монолите `UserLastLoginDateService` обновляет дату на каждое событие `UserLoadedEvent` (любая загрузка профиля, в т.ч. при промахе кэша); в identity-service дата фиксируется по факту входа/выпуска токена — изменение поведения, фиксируется осознанно.

## Данные и хранилища

- **Redis (User cache)** — профиль и роли (ADR-01), чтобы не ходить в ОШС/ЮСБ на каждый запрос; короткий локальный Caffeine-кэш поверх;
- **Своя БД** — локальные админы и роли, реестр выданных/отозванных токенов, аудит входов;
- реплики identity-service используют общий реестр PostgreSQL для выдачи, обмена и отзыва JWT. Запрос может обработать любая реплика. Потребители проверяют подпись по JWKS автономно. До катофа в проде sticky остаётся на стороне монолита (см. ADR-02).

## API (целевой контур)

Внешние URL без изменений (portal-edge проксирует в identity-service):

- `/login`, `/logout`, `/prepare-logout`, `/end-logout`;
- `/user/current/profile` (включая `/workphone` и POST — сохранение кастомных полей), `/user/current/profile/organizations`, `/user/current/profile/organizations/active` (GET/PUT — установка cookie `ACTIVE_ORG`);
- `/currentuser`, `/admin/currentuser` (HEAD, заголовок `user`);
- `/roles`, `/roles/{id}`;
- `/api/v1/userinfo/cache/invalidate` (сейчас GET с сайд-эффектом — при переносе перевести на POST/DELETE).

Публичные и внутренние:

- `GET /.well-known/jwks.json` — публичные ключи для portal-edge и nav-composer;
- `POST /api/v1/token` — первичная выдача JWT по идентификатору, подтверждённому внешним SSO. В текущем контракте идентификатор — канонический логин;
- `POST /api/internal/token/exchange` — однократный обмен зарегистрированного действующего Bearer JWT с перечитыванием прав. По умолчанию также требуется `X-Internal-Key`.
- `POST /api/v1/logout` — отзыв предъявленного Bearer JWT. Выданный ранее преемник и другие токены пользователя сохраняют собственный срок действия;
- админ-API локальных пользователей и ролей для portal-app (`UsersController`, `RolesController`, `UserRoleService`, `UserService`) — админ-UI остаётся в portal-app, данные уходят в identity-service;
- канал аудита: события входов/выпуска токенов для админ-контура аудита portal-app (`AdminAuditEventMapper`, server-audit) — kafka/rabbit/API.

## Открытые решения (требуют согласования)

1. **Продление токена**: короткий access + token-exchange в identity-service. Ограничение на legacy снято (в проде JWT не использовался), но обязательны: абсолютный срок жизни, перечитывание ролей при exchange, реестр отозванных токенов.
2. **Активная организация**: claim в JWT против состояния в Redis. Рекомендация — claim в JWT (`org_guid` + `org_code`): потребители находятся в горячих путях portal-edge (заголовок бэкендам) и portal-app (`/document-link`, тикеты). Cookie `ACTIVE_ORG` сохранить для обратной совместимости и логаута.
3. **Единый формат claims** — фактически снят: оба legacy-формата (`sub`+`auth` и nav-composer `username`+`roles`+`requestScheme`) не прод; JWT nav-composer — отладочный. Проектируется один формат с нуля: `username`, `roles`, `org_guid`/`org_code`, источник аутентификации; `requestScheme` — из `X-Forwarded-Proto` (не из Referer).
4. **Переключение на JWT в проде**: катоф по переключателю portal-edge (`auth-mode: passthrough → jwt`) с планом отката (см. ADR-02). До катофа SSO-заголовок пробрасывается как есть; после — вычищается на edge и подставляется проверенный.

## Статус реализации (18.11.2026)

Проект создан в `C:\dev\identity-service` (ASCII-путь: на Windows лаунчер JDK читает `@argfile` Gradle в cp1251, и кириллица в пути ломает тест-воркеры — известная проблема Gradle/JDK).

Стек: Java 25, Spring Boot 4.1.2, WebFlux (Netty), Jackson 3, etcd (jetcd), Resilience4j (command-support), Lombok.
Модули: `identity-app` (+ скопированные из soa/nav-composer: `etcd-client`, `config-source`, `command-support`, `soap-client`, `soap-wsdl-resolver`, `webclient-support`), `profile-support`, `token-support`.

Реализовано:

- M0: каркас, сборка и тесты зелёные;
- M1: конфиги из etcd — `OSSConfig`/`UsbConfig` (subset полей), watch + события изменения, сброс кэшей;
- M2: ядро профиля — `OssConnection` (SOAP СОБИ через soap-client + WSDL-resolver, REST USS v1/v2, перепаковка в формат ОШС), команды с Resilience4j, `ProfileService` (реактивный, Caffeine-кэш, автовыбор единственной организации), внутренний API `/api/internal/v1/users/...` (`X-Internal-Key` по умолчанию);
- M3: локальные админы (`user/123`, `user2/1234`, BCrypt) и роли (PostgreSQL, H2 в тестах), `GET /roles`, `/roles/{id}`;
- M4: JWT — RS256, ключ при старте (kid `identity-1`), `/api/v1/token` (SSO-заголовок с cookie-fallback и `^M`, либо локальный логин/пароль), `/api/internal/token/exchange` (перечитывание ролей, проверка отзыва), `/api/v1/logout` (реестр jti в БД), `/.well-known/jwks.json`; claims: `sub`, `username`, `roles[]`, `org_guid`, `org_code`, `auth_source`.

После M5 реализованы постоянные ключи, ротация и ограничения жизненного цикла JWT. Остались мTLS для внутреннего API, rate-limiting на `/api/v1/token`, прогон в dev-контуре против реальных ОШС/ЮСБ, интеграция с fk-portal через feature-toggle (следующий шаг, вне текущего плана).

## Возможности сервиса

### 1. Конфигурация из etcd

- Ключи `fk-portal/OSSConfig` и `fk-portal/UsbConfig` (DTO только с необходимыми полями);
- hot-reload: watch → событие изменения → сброс кэшей бэкендов и профилей, без рестарта.

### 2. «Прослойка» между монолитом и ОШС/ЮСБ — внутренний API (без JWT)

По умолчанию `identity.internal.require-api-key=true` и сервис проверяет `X-Internal-Key` на всех маршрутах `/api/internal/**`. В переходном режиме `IDENTITY_INTERNAL_REQUIRE_API_KEY=false` отключает эту проверку. Сетевой доступ к методам профиля, групп, организаций и изменения атрибутов должен быть ограничен доверенными сервисами.

Методы `POST /api/internal/v1/users/{login}/...` принимают логин в пути.

- `/profile` — профиль + роли + организации (агрегация userInfo + groups);
- `/groups` — роли пользователя;
- `/custom-attributes` — редактирование кастомных полей. При получении SOAP-ответа `SimpleResponse` сервис сбрасывает профиль из кэша и отвечает HTTP 204. Для ЮСБ API возвращает 501, при тайм-ауте источника — 504, при SOAP Fault или пустом ответе — 503. Значения поля `SimpleResponse.result` требуют подтверждения по контракту СОБИ в dev-контуре.

Список организаций доступен по `POST /api/internal/v1/users/organizations/{login}`.

Источники: СОБИ SOAP (JAXB-контракт `fk-services-sobi`, WSDL-resolver для endpoint/SOAPAction с fallback как в монолите), USS REST v1/v2 (перепаковка в формат ОШС, включая админские роли из групп), СОБИ REST — заглушка. На каждый вызов — Resilience4j (таймауты/конкурентность из конфига); Caffeine-кэш профилей с метриками; автовыбор организации, если она одна; пустые группы → `ROLE_UNDEFINED` (поведение монолита сохранено).

### 3. Локальные админы и роли (PostgreSQL)

- Для опционального локального входа оператор заводит пользователя с BCrypt-паролем и ролями. По умолчанию включён только SSO;
- `GET /roles`, `GET /roles/{id}` — встроенные роли `ROLE_ADMIN`/`ROLE_USER`/`ROLE_EDITOR`;
- Миграция V4 добавляет недостающие встроенные роли и сохраняет названия, заданные оператором.
- Схема БД мигрируется **Flyway** при старте (`db/migration`, `baseline-on-migrate`; в Boot 4 автоконфигурация Flyway удалена — бин конфигурируется вручную).

### 4. Выпуск JWT (RS256, JWKS)

- Постоянный RSA-ключ загружается из PKCS#8 PEM. Набор открытых ключей поддерживает период перекрытия при ротации.
- `POST /api/v1/token` выпускает access-токен по доверенному SSO-заголовку. Вход с локальным паролем и SSO-cookie включаются отдельно.
- `POST /api/internal/token/exchange` требует действующий Bearer JWT при любом значении `identity.internal.require-api-key` и однократно обменивает его с перечитыванием прав. Цепочка обменов ограничена временем от первичной выдачи токена порталом.
- `POST /api/v1/logout` отзывает предъявленный токен для дальнейшего обмена. При автономной проверке в portal-edge действует TTL токена.
- `GET /.well-known/jwks.json` публикует активный и дополнительные открытые ключи с настраиваемым сроком кэширования.

Порядок ротации и перехода с первой версии описан в [README](../README.md#ключи-и-ротация).

### Осознанные ограничения (что пока НЕ умеет)

- нет rate-limiting на `/api/v1/token`; для внутреннего API по умолчанию включена проверка общего ключа, в дальнейшем запланирован mTLS;
- нет хранения выбранной организации для мультиорганизационных пользователей (в токен попадает только автовыбранная единственная);
- нет публичных профильных эндпоинтов (`/user/current/profile*`) и интеграции с монолитом через feature-toggle — следующий шаг;
- не прогнан против реальных ОШС/ЮСБ в dev-контуре (тесты — WireMock/моки); Docker-образ и CI подготовлены, но не запускались.
