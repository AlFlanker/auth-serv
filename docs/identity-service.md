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
| Активная организация, локальные админы | Sticky-сессии, отдача UI (portal-edge / portal-app) |

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
- `PortalHttpsChannelFilter` — канальные редиректы HTTP/HTTPS по layout-атрибутам (замена `ChannelProcessingFilter`), к клиентским сертификатам отношения не имеет; уходит с layout-правилами в portal-edge/portal-app, не в identity-service. Клиентские сертификаты (`CertificateUtils`/`CertificateUtilsImpl`) — мёртвый код, в identity-service не переносится (см. п. 6).

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
- клиентские сертификаты: `CertificateUtils`/`CertificateUtilsImpl` (`core/security`, пакет cert) — бин объявляется в `WebSecurityConfiguration`, но ни один компонент его не вызывает (`getCertInfo` без потребителей); `ExtensionUserDetail.certInfo` никогда не заполняется, поэтому заголовки УФОС-прокси по сертификату (`EbUIHeaderInterceptor` и др.) и сейчас не формируются. Мёртвый код, кандидат на удаление; при необходимости сертификатной аутентификации — реализация с нуля.

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
- `POST /api/internal/token/exchange` — однократный обмен зарегистрированного действующего Bearer JWT с перечитыванием прав. Запрос требует `X-Internal-Key`;
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
- M2: ядро профиля — `OssConnection` (SOAP СОБИ через soap-client + WSDL-resolver, REST USS v1/v2, перепаковка в формат ОШС), команды с Resilience4j, `ProfileService` (реактивный, Caffeine-кэш, автовыбор единственной организации, признак `cacheHit`), внутренний API `/api/internal/v1/users/...` с телом запроса (защита `X-Internal-Key`);
- M3: локальные админы (`user/123`, `user2/1234`, BCrypt) и роли (PostgreSQL, H2 в тестах), `GET /roles`, `/roles/{id}`;
- M4: JWT — RS256, ключ при старте (kid `identity-1`), `/api/v1/token` (SSO-заголовок с cookie-fallback и `^M`, либо локальный логин/пароль), `/api/internal/token/exchange` (перечитывание ролей, проверка отзыва), `/api/v1/logout` (реестр jti в БД), `/.well-known/jwks.json`; claims: `sub`, `username`, `roles[]`, `org_guid`, `org_code`, `auth_source`.

После M5 реализованы постоянные ключи, ротация и ограничения жизненного цикла JWT. Остались мTLS для внутреннего API, rate-limiting на `/api/v1/token`, прогон в dev-контуре против реальных ОШС/ЮСБ и включение переключателя в fk-portal на стендах.

## Возможности сервиса

### 1. Конфигурация из etcd

- Ключи `fk-portal/OSSConfig` и `fk-portal/UsbConfig` (DTO только с необходимыми полями);
- hot-reload: watch → событие изменения → сброс кэшей бэкендов и профилей, без рестарта.

### 2. «Прослойка» между монолитом и ОШС/ЮСБ — внутренний API (без JWT)

Все методы — `POST`, идентификатор пользователя и параметры передаются **в теле запроса** (в путях только статичные сегменты), защита заголовком `X-Internal-Key`:

- `/api/internal/v1/users/profile` — тело `{"login","bypassCache"}`; ответ: профиль + роли + организации с признаком `cacheHit` (`true` — отдан из кэша сервиса, `false` — загружен из источника; при `bypassCache=true` всегда `false`);
- `/api/internal/v1/users/groups` — тело `{"login"}`; роли пользователя (без кэша);
- `/api/internal/v1/users/organizations` — тело `{"login"}`; организации пользователя из профиля;
- `/api/internal/v1/users/custom-attributes` — тело `{"login","attributes"}`; редактирование кастомных полей. При получении SOAP-ответа `SimpleResponse` сервис сбрасывает профиль из кэша и отвечает HTTP 204. Для ЮСБ API возвращает 501, при тайм-ауте источника — 504, при SOAP Fault или пустом ответе — 503. Значения поля `SimpleResponse.result` требуют подтверждения по контракту СОБИ в dev-контуре.
- `/api/internal/v1/users/cache/invalidate` — без тела; полная инвалидация кэша профилей (HTTP 204).

Источники: СОБИ SOAP (JAXB-контракт `fk-services-sobi`, WSDL-resolver для endpoint/SOAPAction с fallback как в монолите), USS REST v1/v2 (перепаковка в формат ОШС, включая админские роли из групп), СОБИ REST — заглушка. На каждый вызов — Resilience4j (таймауты/конкурентность из конфига); Caffeine-кэш профилей с метриками; автовыбор организации, если она одна; пустые группы → `ROLE_UNDEFINED` (поведение монолита сохранено).

### 2а. Интеграция с fk-portal через feature-toggle (реализовано в монолите)

- Переключатель: `OSSConfig.redirectToExternalService` (конфиг-БД/UI, по умолчанию выключен) + `OSSConfig.externalIdentityServiceUrl` — базовый URL внутреннего API. Значения попадают в etcd (`fk-portal/OSSConfig`) и игнорируются сервисом (subset-DTO).
- Секрет `admin.identity-service.api-key` (application.yml/secret-manager, совпадает с `identity.internal.api-key`) — в конфиг-БД не хранится.
- В монолите `OSSUserDetailsService` при включённом переключателе направляет загрузку профиля/ролей/кастомных полей в `IdentityServiceClient` (WebClient, пул `IDENTITY_SERVICE`, `X-Internal-Key`); **локальный кэш монолита в этом режиме не наполняется** — кэширует только identity-service.
- Событие `UserLoadedEvent` (потребитель — `UserLastLoginDateService`) публикуется только при `cacheHit=false`, т.е. на реальных загрузках из источника — как сегодня при промахах локального кэша.
- Инвалидация кэша (`/api/v1/userinfo/cache/invalidate`) во внешнем режиме вызывает `/api/internal/v1/users/cache/invalidate`.
- Откат — выключить переключатель (без правок БД/etcd); `OssConfigChangedEvent` пересоздаёт WebClient клиента без рестарта.

**Локальный запуск (docker compose).** Сервис добавлен в compose-стенд портала (`solutions/fk-portal/docker-compose.yml`: сервисы `identity-service` и `jaeger`); профиль `docker` (`application-docker.yml`) подключается к уже работающим на хосте etcd/PostgreSQL через host-gateway, постоянный ключ подписи JWT монтируется с хоста (`C:\Users\AlexFlanker\AppData\Local\identity-service\keys`, по умолчанию `identity-dev-01.pem`, kid `identity-dev-01`), локальный вход `user/123` включён, трассы — все в Jaeger (UI `http://localhost:16686`). Запуск: `docker compose build identity-service` → `docker compose up -d jaeger` → `docker compose up -d --no-deps identity-service`.

### 2б. Публичные профильные эндпоинты (JWT)

Реализованы (19.11.2026) с контрактами монолита — после катофа portal-edge проксирует эти URL в identity-service без изменений:

| Метод и путь | Контракт |
|---|---|
| `GET /user/current/profile` | `OSSUserInfo` `{employee, organization}`; поля — `ProfileField {value, editable}`; `phoneWorkplace.editable=true`; `Cache-Control: no-cache` |
| `GET /user/current/profile/workphone` | рабочий телефон из источника без кэша (`loadWithoutCache`) |
| `POST /user/current/profile` | сохранение `phoneWorkplace` (200). Обновляется только владелец JWT — логин из тела игнорируется (в монолите логин из тела управлял целью) |
| `GET /user/current/profile/organizations` | список организаций `{organizationCode, organizationName, active}`; `active` — статус реорганизации `ACTIVE` (как в монолите) |
| `GET/PUT /user/current/profile/organizations/active` | активная организация; PUT валидирует по списку организаций владельца и ставит cookie `ACTIVE_ORG` (`Path=/; HttpOnly; SameSite=Strict`, значение URL-encoded); неизвестный код — 404 `ORGANIZATION_NOT_FOUND` (в монолите — 500) |
| `HEAD /currentuser` | заголовок `user` + no-cache заголовки |
| `HEAD /admin/currentuser` | то же при `ROLE_ADMIN` в JWT, иначе 403 |

- Аутентификация: `JwtAuthenticationWebFilter` на путях `/user/current/**`, `/currentuser`, `/admin/currentuser` — автономная проверка Bearer JWT по подписи и сроку (как на portal-edge, без реестра jti); логин из `sub`, роли из `roles`.
- Активная организация: claim `org_code` → cookie `ACTIVE_ORG` → единственная организация; иначе **428** `{errorCode: "ORG_NOT_SELECTED", error}` — контракт `OrganizationGuardFilter`, UI по нему открывает модалку выбора (в монолите в этом случае был 500).
- Локальные пользователи (auth_source=password): профильные эндпоинты — 404 `NOT_FOUND` (в монолите 200 с `null`/405); `/currentuser` работает, `/admin/currentuser` — по роли.
- Сбои источника — 503/504 `{code,message}`, как во внутреннем API. Постоянное хранение выбора активной организации и выдача claims при выпуске/обмене — п. 2 таблицы недостающих функций.

### 3. Локальные админы и роли (PostgreSQL)

- Для опционального локального входа оператор заводит пользователя с BCrypt-паролем и ролями. По умолчанию включён только SSO;
- `GET /roles`, `GET /roles/{id}` — встроенные роли `ROLE_ADMIN`/`ROLE_USER`/`ROLE_EDITOR`;
- Миграция V4 добавляет недостающие встроенные роли и сохраняет названия, заданные оператором.
- Схема БД мигрируется **Flyway** при старте (`db/migration`, `baseline-on-migrate`; в Boot 4 автоконфигурация Flyway удалена — бин конфигурируется вручную).

### 4. Выпуск JWT (RS256, JWKS)

- Постоянный RSA-ключ загружается из PKCS#8 PEM. Набор открытых ключей поддерживает период перекрытия при ротации.
- `POST /api/v1/token` выпускает access-токен по доверенному SSO-заголовку. Вход с локальным паролем и SSO-cookie включаются отдельно.
- claims `roles` — полный список групп ОШС; у реальных пользователей их сотни, токен достигает ~17 КБ и не помещается в дефолтные лимиты HTTP-заголовков (431) — см. п. 9 таблицы недостающих функций;
- `POST /api/internal/token/exchange` однократно обменивает действующий токен с перечитыванием прав. Цепочка обменов ограничена временем от первичной выдачи токена порталом.
- `POST /api/v1/logout` отзывает предъявленный токен для дальнейшего обмена. При автономной проверке в portal-edge действует TTL токена.
- `GET /.well-known/jwks.json` публикует активный и дополнительные открытые ключи с настраиваемым сроком кэширования.

Порядок ротации и перехода с первой версии описан в [README](../README.md#ключи-и-ротация).

## Недостающие функции: кому и зачем (статус 19.11.2026)

Ядро для целевого контура готово: единый формат claims, JWKS с ротацией, выпуск/обмен/отзыв JWT
(реестр в БД), сбор профиля/ролей из ОШС/ЮСБ с кэшем и `cacheHit`, внутренний API за `X-Internal-Key`,
интеграция с монолитом за feature-toggle, публичные профильные эндпоинты (`/user/current/profile*`,
`/currentuser`, `/admin/currentuser`), локальные пользователи/роли (чтение), конфиги из etcd.

До полного катофа (шаги 4–5 миграции, ADR-02) не хватает:

| № | Функция | Кому и зачем |
|---|---|---|
| 1 | Разовый/ротируемый `AUTH_TOKEN` для админ-контура | portal-edge: проверка доступа к `/admin` в jwt-режиме вместо статического токена на JVM монолита (`TokenAuthFilter`), печатаемого в лог |
| 2 | Активная организация мультиорганизационных пользователей: постоянное хранение выбора и выдача `org_guid`/`org_code` в claims при выпуске/обмене JWT | portal-edge — заголовок «код СВР» бэкендам (`ActiveOrgHeaderEnricher`); portal-app — `/document-link` (org-GUID для УФОС) и обогащение тикетов (`ELKProblemReportEnricher`). Эндпоинты GET/PUT active и cookie `ACTIVE_ORG` уже реализованы (см. «Возможности сервиса», п. 2б) — выбор пока хранится в cookie клиента, а не на сервере |
| 3 | Админ-API локальных пользователей и ролей: CRUD пользователей (`UsersController`/`UserService`), ролей и назначений (`RolesController`/`UserRoleService`) | portal-app: админ-UI остаётся в Common, данные — в identity-service; сейчас есть только bootstrap-пользователи и чтение ролей (`GET /roles`, `/roles/{id}`) |
| 4 | Канал аудита входов/выпуска/обмена/отзыва токенов (kafka/rabbit/API) | portal-app (server-audit, `AdminAuditEventMapper`): журнал входов продолжает работать после выноса аутентификации |
| 5 | SSO-logout контур: `PoibLogoutUrlResolver`, `SsoLogoutUrlResolver` (`/prepare-logout`, `/end-logout`), `UfosLogoutHandler` (logout УФОС, из `ufos-support`) | внешние `/login`, `/logout`, `/prepare-logout`, `/end-logout` остаются без изменений; сейчас есть только отзыв JWT — без резолверов завершение SSO-сессии ломается |
| 6 | Харденинг: mTLS внутреннего API и rate-limiting на `/api/v1/token` | прод-эксплуатация: потребителей внутреннего API становится несколько (edge, portal-app, nav-composer) — общий ключ слабая защита; `/api/v1/token` — точка перебора |
| 7 | Распределённый кэш профилей (Redis/gRPC, ADR-01) поверх локального Caffeine | несколько реплик: общий кэш снимает рассинхрон профилей/ролей между репликами и лишнюю нагрузку на ОШС/ЮСБ; сейчас сквозная инвалидация — ручная (`cache/invalidate`) |
| 8 | Переработка JWT у nav-composer (вне identity-service, но блокирует единый формат) | nav-composer пока принимает отладочный HMAC-формат (`NavComposerJwtFactory`: `username`, `roles`, `requestScheme`); целевой — верификация по JWKS identity-service или приём проверенного JWT от edge, иначе единый формат claims не доходит до проверок доступа |
| 9 | Компактность JWT: claims `roles` — полный список групп ОШС, у реальных пользователей их сотни («Полномочия»), токен достигает ~17 КБ и не проходит дефолтный лимит HTTP-заголовков (Netty/nginx — 8 КБ, ответ 431) | portal-edge и все сервисы, принимающие Bearer: до катофа сократить claims (компактный набор ролей/типов вместо списков полномочий) либо согласованно поднять лимиты заголовков; на локальном стенде лимиты подняты в docker compose |

Не входят в identity-service и уходят в другие контуры (по ADR-02): локальные URL-правила `CheckRoutesFilter`
(portal-edge), проверки доступности ресурсов СПИ/ЮСБ (nav-composer), sticky-сессии и отдача UI (portal-edge/portal-app).

### Осознанные ограничения (что пока НЕ умеет)

- нет rate-limiting на `/api/v1/token`; внутренний API защищён общим ключом (мTLS позже);
- выбор активной организации мультиорганизационных пользователей хранится только в cookie клиента (`ACTIVE_ORG`): нет постоянного серверного хранения и выдачи `org_guid`/`org_code` в claims при выпуске/обмене (п. 2 таблицы недостающих функций);
- интеграция с монолитом через feature-toggle реализована, но на стендах переключатель ещё не включён;
- не прогнан против реальных ОШС/ЮСБ в dev-контуре (тесты — WireMock/моки); Docker-образ собран и проверен локально в docker compose, на стендах не развёрнут.
