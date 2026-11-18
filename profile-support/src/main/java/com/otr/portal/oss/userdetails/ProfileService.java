package com.otr.portal.oss.userdetails;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.otr.portal.command.Command;
import com.otr.portal.config.source.ConfigKey;
import com.otr.portal.config.source.ConfigSource;
import com.otr.portal.oss.config.OssConfig;
import com.otr.portal.oss.config.OssConfigChangedEvent;
import com.otr.portal.oss.config.UsbConfig;
import com.otr.portal.oss.exception.ProfileNotFoundException;
import com.otr.portal.oss.model.Organization;
import com.otr.portal.oss.model.Profile;
import com.otr.portal.oss.userdetails.command.OssCommandFactory;
import com.otr.portal.oss.userdetails.command.model.GetAuthoritiesRequest;
import com.otr.portal.oss.userdetails.command.model.GetUserDetailsRequest;
import com.otr.portal.oss.userdetails.command.model.ManageUserCustomAttributesRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.otr.hr.ws.organization.v3.GetUserInfoResponse;
import ru.otr.hr.ws.organization.v3.TEmployee;
import ru.otr.hr.ws.organization.v3.TUserInfo;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.emptyList;

/**
 * Фасад сбора профиля и ролей пользователя из ОШС/ЮСБ - полностью реактивный аналог
 * монолитного {@code OSSUserDetailsService} (без Spring Security и сессий).
 */
@Slf4j
@Service
public class ProfileService {

    private static final int DEFAULT_CACHE_TTL_MINUTES = 60;

    private final OssCommandFactory commandFactory;
    private final ConfigSource configSource;
    private final ConfigKey<OssConfig> ossConfigKey;
    private final ConfigKey<UsbConfig> usbConfigKey;
    private final ApplicationEventPublisher eventPublisher;

    private volatile AsyncLoadingCache<String, Profile> cache = buildCache(DEFAULT_CACHE_TTL_MINUTES);
    private volatile int cacheTtlMinutes = DEFAULT_CACHE_TTL_MINUTES;

    public ProfileService(OssCommandFactory commandFactory,
                          ConfigSource configSource,
                          ConfigKey<OssConfig> ossConfigKey,
                          ConfigKey<UsbConfig> usbConfigKey,
                          ApplicationEventPublisher eventPublisher,
                          MeterRegistry meterRegistry) {
        this.commandFactory = commandFactory;
        this.configSource = configSource;
        this.ossConfigKey = ossConfigKey;
        this.usbConfigKey = usbConfigKey;
        this.eventPublisher = eventPublisher;
        CaffeineCacheMetrics.monitor(meterRegistry, cache.synchronous(), "profileCache");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refreshCacheTtl();
    }

    @EventListener
    public void onOssConfigChanged(OssConfigChangedEvent ignored) {
        refreshCacheTtl();
    }

    /**
     * Загружает профиль пользователя (с кэшем).
     */
    public Mono<Profile> load(String username) {
        return Mono.defer(() -> Mono.fromFuture(cache.get(username)));
    }

    /**
     * Загружает профиль пользователя, минуя кэш.
     */
    public Mono<Profile> loadWithoutCache(String username) {
        return loadFromOss(username);
    }

    /**
     * Группы/роли пользователя (без кэша профиля).
     */
    public Mono<List<String>> getUserAuthorities(String username) {
        return config().flatMap(cfg -> loadAuthorities(username, cfg));
    }

    /**
     * Список организаций пользователя.
     */
    public Mono<List<Organization>> getOrganizations(String username) {
        return load(username).map(Profile::organizations);
    }

    /**
     * Обновляет кастомные атрибуты пользователя в источнике и инвалидирует кэш профиля.
     */
    public Mono<Void> updateCustomFields(String username, java.util.Map<String, String> attributes) {
        return commandFactory.buildCommand(ManageUserCustomAttributesRequest.builder()
                        .username(username)
                        .setAttributes(attributes)
                        .build())
                .flatMap(Command::execute)
                .doOnSuccess(ignored -> invalidate(username))
                .then();
    }

    /**
     * Полная инвалидация кэша профилей.
     */
    public void invalidateAll() {
        log.warn("Вызвана инвалидация кэша профилей");
        cache.synchronous().invalidateAll();
    }

    public void invalidate(String username) {
        cache.synchronous().invalidate(username);
    }

    private void refreshCacheTtl() {
        configSource.get(ossConfigKey)
                .subscribe(config -> {
                            var ttlMinutes = config.cacheTimeMin() > 0 ? config.cacheTimeMin() : DEFAULT_CACHE_TTL_MINUTES;
                            if (ttlMinutes != cacheTtlMinutes) {
                                cacheTtlMinutes = ttlMinutes;
                                this.cache = buildCache(ttlMinutes);
                                log.info("Кэш профилей пересоздан с TTL {} мин", ttlMinutes);
                            }
                        },
                        error -> log.warn("Не удалось прочитать OssConfig для настройки кэша: {}", error.getMessage()));
    }

    private AsyncLoadingCache<String, Profile> buildCache(int ttlMinutes) {
        return Caffeine.newBuilder()
                .expireAfterAccess(ttlMinutes, TimeUnit.MINUTES)
                .initialCapacity(1000)
                .recordStats()
                .buildAsync((username, executor) -> loadFromOss(username).toFuture());
    }

    private Mono<Profile> loadFromOss(String username) {
        return config()
                .flatMap(cfg -> Mono.zip(
                                commandFactory.buildCommand(GetUserDetailsRequest.from(username))
                                        .flatMap(Command::execute),
                                loadAuthorities(username, cfg))
                        .map(tuple -> toProfile(tuple.getT1(), tuple.getT2(), username, cfg)))
                .doOnSuccess(profile -> eventPublisher.publishEvent(new ProfileLoadedEvent(profile)))
                .doOnSuccess(profile -> log.trace("Получен пользователь из {}: [{}]", profile.login(), username));
    }

    private Mono<List<String>> loadAuthorities(String username, OssConfig cfg) {
        Mono<Boolean> groupsEnabled = cfg.ossType() == OssConfig.OssType.USB
                ? configSource.get(usbConfigKey)
                        .map(UsbConfig::usbGroupEnabled)
                        .defaultIfEmpty(false)
                : Mono.just(cfg.ossGroupEnabled());
        return groupsEnabled.flatMap(enabled -> enabled
                ? commandFactory.buildCommand(GetAuthoritiesRequest.from(username))
                        .flatMap(Command::execute)
                : Mono.just(emptyList()));
    }

    private Mono<OssConfig> config() {
        return configSource.get(ossConfigKey)
                .switchIfEmpty(Mono.error(new IllegalStateException("Конфигурация OSSConfig не найдена в etcd")));
    }

    private @NotNull Profile toProfile(GetUserInfoResponse response, List<String> roles, String username, OssConfig cfg) {
        List<TUserInfo> userInfos = response.getUserInfos().getUserInfo();
        TUserInfo primary = userInfos.stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new ProfileNotFoundException(username));

        TEmployee employee = primary.getEmployee();
        List<Organization> organizations = userInfos.stream()
                .filter(Objects::nonNull)
                .map(Organization::from)
                .toList();

        String login = cfg.ossType() == OssConfig.OssType.USB
                ? employee.getSamAccountName()
                : employee.getLogin();

        String orgName = null;
        String orgFullName = null;
        String activeOrgCode = null;
        String activeOrgGuid = null;
        if (organizations.size() == 1) {
            var single = organizations.get(0);
            orgName = single.organizationName();
            orgFullName = single.organizationFullName();
            activeOrgCode = single.orgSvrCode();
            activeOrgGuid = single.orgGUID();
        }

        return new Profile(
                login,
                employee.getFullName(),
                employee.getDepartmentName(),
                employee.getTitleName(),
                employee.getPhoneWorkplace(),
                employee.getPhoneOther(),
                employee.getSnils(),
                employee.getEmail(),
                employee.getSamAccountName(),
                employee.getAccountGuid(),
                orgFullName,
                orgName,
                roles,
                organizations,
                activeOrgCode,
                activeOrgGuid);
    }
}
