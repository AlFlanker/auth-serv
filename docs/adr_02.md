# ADR-02 (черновик): Целевой состав сервисов и зоны ответственности

Статус: черновик — уточнение ADR-01
Дата: 16.11.2026

## Контекст

ADR-01 принят (Вариант 2, SOA, единая точка входа, обратная совместимость). В тексте ADR два перечня сервисов по 5 позиций, которые различаются: в описании варианта — «Comms Service», в принятом решении — «Common Service». Фиксируем: «Comms» — это Common Service; уведомления и обращения на первом этапе живут внутри Common. Итоговый целевой состав — **5 сервисов**.

Кодовые имена сервисов:

| Сервис | Кодовое имя |
|---|---|
| Edge/API Gateway | **portal-edge** |
| Navigation Service | **nav-composer** (существующее) |
| Auth Service | **identity-service** |
| Config Service | **config-center** |
| Common Service | **portal-app** |

## Сервисы

### 1. portal-edge (Edge/API Gateway)

Единая точка входа, внешний API без изменений.

- Проксирование к бэкендам: перенос самописного механизма (`extensions/backends/proxy`) — маршрутизация, cookies/SSO, переписывание ссылок, стриминг, кэш, SSL, метрики, logout.
- Верификация JWT на входе (stateless), локальные URL-правила, sticky-сессии.
- Аутентификация — разъём `auth-mode`: `passthrough` (проброс SSO-заголовка, единственного прод-механизма авторизации; используется до готовности identity-service и как план отката) → `jwt` (верификация по JWKS; в этом режиме клиентский SSO-заголовок вычищается и подставляется проверенный из claims). Sticky-сессии нужны только до перехода в `jwt`.
- Отдача статики фронта / BFF-агрегация.
- Проверку доступа перед проксированием делегирует в nav-composer (вместо in-process `NavigationProxyAccessFilter`).
- Новый код: Spring Boot 3 / WebFlux / Resilience4j.

### 2. nav-composer (Navigation Service) — реализован

Построение дерева навигации + проверки доступности.

- Деревья root/context/items, фильтрация по ролям из JWT.
- Проверки доступности: СПИ (batch) и ЮСБ; признак деградации (`accessCheck.degraded`, `X-Navigation-Degraded`).
- Внешние навигации УФОС (SOAP + Resilience4j); SOAP-API наружу.
- Конфиги — только чтение через config-source (etcd read-only + watch).
- Модули: static-navigation, navigation-ufos, navigation-backend-portal, navigation-soapserver, soap-clients (navigation/spi), spi-support, usb-support (проверки), ufos-support.
- `ufos-support` — сборный модуль; при миграции разнести: documentLink (`/document-link`) → portal-app, `UfosLogoutHandler` → identity-service, proxy-части (`EbUIHeaderInterceptor`, `EBHeaderTemplateList`) → portal-edge.

### 3. identity-service (Auth Service)

Аутентификация и профиль пользователя.

- Выдача JWT, logout/SSO, сертификаты.
- Сбор профиля из ОШС/ЮСБ (`OSSUserDetailsService` и источники).
- Верификация JWT — на стороне portal-edge; цель — асимметричная подпись (JWKS) вместо общего HMAC-секрета.

### 4. config-center (Config Service)

Источник истины конфигураций.

- Единственный писатель в etcd (RBAC: остальные сервисы read-only).
- Админ-API: CRUD, валидация, версии/история, импорт/экспорт, миграции.
- Реестр схем конфигураций (сервисы регистрируют схемы при старте).
- Доставка изменений: etcd watch → config-source (общая библиотека) → локальный кэш в каждом сервисе. Config Service не стоит в горячем пути чтения; его деградация не блокирует чтение конфигов.

### 5. portal-app (Common Service)

Всё, что не вошло в контуры выше, со своей БД (Configuration DB).

- Контент/страницы/медиа, виджеты и избранное, поиск, документация, открытие документов по ссылке (`/document-link`, из `ufos-support`).
- Админ-контур: admin UI, server-management, monitoring, inventory, health, audit.
- jobs, calendar, avatar, whats-new, интеграции (ТОФК, УФОС-фискальный журнал, Moodle, autosavedoc).
- Специфика fk-portal (workplace, темы), отдача SPA.
- На первом этапе внутри Common — контур «уведомления + обращения» (messages + бэкенды, internal-user-notifications, problem-report + storages, naumen-chat, mail/firebase/rabbitmq/kafka, websocket) за существующими SPI-границами; выделение в отдельный сервис — только по необходимости.

## Сквозные решения

- **config-source** — единая клиентская библиотека (etcd read-only + watch + локальный кэш) во всех сервисах.
- **Проверки доступа** — Gateway/Common зовут API навигации, а не дублируют её пайплайн (в т.ч. Избранное).
- **Shared kernel** — модель Backend/Host, wire-DTO контракты, версионирование.
- **JWT** — единый формат claims, проектируемый с нуля: `username`, `roles`, `org_guid`/`org_code`, источник аутентификации; подпись — JWKS. Legacy-формат nav-composer (`username`, `roles`, `requestScheme`) — отладочный, не является целевым (см. identity-service.md).
- **Observability** с первого дня: метрики/трейсы/логи, сквозной requestId.

## Порядок миграции и стратегия катофа

Сервисы в прод переносятся **не поштучно**: все плавные переходы отрабатываются в dev/test-контурах; в прод — скоординированный катоф по переключателям. Поэтому порядок ниже — это порядок разработки и интеграции, а не прод-релизов.

1. nav-composer — выполнено (переключатель в fk-portal). JWT интеграции nav-composer ↔ fk-portal — отладочный механизм, перерабатывается вместе с identity-service (единый формат claims).
2. Фундамент (раньше сервисов, design-time): shared kernel, единый формат claims (включая `org_guid`/`org_code` и источник аутентификации), JWKS-контракт, библиотека config-source.
3. Параллельная разработка: portal-edge (милестоуны: passthrough-режим против монолита → JWT-режим), identity-service (+ асимметричные ключи), config-center.
4. Интеграция в dev/test: edge-passthrough → identity standalone → edge в JWT-режиме (весь внешний контур) → config-center забирает запись в etcd → portal-app.
5. portal-app остаётся «ужатым» монолитом; контур уведомлений выносится только при необходимости.

Условия катофа в прод:

- переход внешнего контура по переключателям (прецедент — переключатель nav-composer в fk-portal);
- план отката — portal-edge в `auth-mode: passthrough` (аутентификация возвращается монолиту);
- до катофа писателем etcd остаётся монолит (`cloud-settings-etcd3`), остальные сервисы читают read-only через config-source; роль единственного писателя config-center забирает на шаге 4 интеграции;
- sticky-сессии снимаются только с переходом edge в `jwt`-режим.
