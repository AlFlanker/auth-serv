package com.otr.portal.oss.userdetails;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.otr.portal.command.Command;
import com.otr.portal.config.source.ConfigKey;
import com.otr.portal.config.source.ConfigSource;
import com.otr.portal.oss.config.OssConfig;
import com.otr.portal.oss.config.OssConfigChangedEvent;
import com.otr.portal.oss.config.UsbConfig;
import com.otr.portal.oss.config.UsbConfigChangedEvent;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.otr.hr.ws.organization.v3.GetUserInfoResponse;
import ru.otr.hr.ws.organization.v3.TUserInfo;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static java.util.Collections.emptyList;

/**
 * Фасад сбора профиля и ролей пользователя из ОШС/ЮСБ — полностью реактивный аналог
 * монолитного {@code OSSUserDetailsService} (без Spring Security и сессий).
 */
@Slf4j
@Service
public class ProfileService {

    private final OssCommandFactory commandFactory;
    private final ConfigSource configSource;
    private final ConfigKey<OssConfig> ossConfigKey;
    private final ConfigKey<UsbConfig> usbConfigKey;
    private final ApplicationEventPublisher eventPublisher;

    private final AsyncLoadingCache<String, CachedProfile> cache;

    @Autowired
    public ProfileService(OssCommandFactory commandFactory,
                          ConfigSource configSource,
                          ConfigKey<OssConfig> ossConfigKey,
                          ConfigKey<UsbConfig> usbConfigKey,
                          ApplicationEventPublisher eventPublisher,
                          MeterRegistry meterRegistry,
                          @Value("${identity.profile.cache.maximum-size:10000}") long maximumSize) {
        this(commandFactory, configSource, ossConfigKey, usbConfigKey, eventPublisher, meterRegistry,
                maximumSize, Caffeine.newBuilder());
    }

    ProfileService(OssCommandFactory commandFactory,
                   ConfigSource configSource,
                   ConfigKey<OssConfig> ossConfigKey,
                   ConfigKey<UsbConfig> usbConfigKey,
                   ApplicationEventPublisher eventPublisher,
                   MeterRegistry meterRegistry,
                   long maximumSize,
                   Caffeine<Object, Object> builder) {
        this.commandFactory = commandFactory;
        this.configSource = configSource;
        this.ossConfigKey = ossConfigKey;
        this.usbConfigKey = usbConfigKey;
        this.eventPublisher = eventPublisher;
        this.cache = builder.maximumSize(maximumSize)
                .expireAfter(Expiry.<String, CachedProfile>writing((username, entry) -> entry.lifetime()))
                .recordStats()
                .buildAsync((username, executor) -> config().flatMap(cfg -> loadFromOss(username, cfg)
                        .map(profile -> new CachedProfile(profile, Duration.ofMinutes(cfg.cacheTimeMin())))).toFuture());
        CaffeineCacheMetrics.monitor(meterRegistry, cache.synchronous(), "profileCache");
    }

    private record CachedProfile(Profile profile, Duration lifetime) {}

    /**
     * Загружает профиль пользователя (с кэшем).
     */
    public Mono<Profile> load(String username) {
        return Mono.defer(() -> {
            var pending = cache.get(username);
            // При повторном запросе после ошибки загружаем профиль заново.
            return Mono.fromFuture(pending, true)
                    .doOnError(error -> cache.asMap().remove(username, pending))
                    .map(CachedProfile::profile);
        });
    }

    /**
     * Загружает профиль пользователя, минуя кэш.
     */
    public Mono<Profile> loadWithoutCache(String username) {
        return config().flatMap(cfg -> loadFromOss(username, cfg));
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
                .doOnNext(ignored -> invalidate(username))
                .then();
    }

    /**
     * Полная инвалидация кэша профилей.
     */
    @EventListener({OssConfigChangedEvent.class, UsbConfigChangedEvent.class})
    public void invalidateAll() {
        log.debug("Сброс кэша профилей");
        cache.asMap().clear();
    }

    public void invalidate(String username) {
        cache.asMap().remove(username);
    }

    private Mono<Profile> loadFromOss(String username, OssConfig cfg) {
        var details = commandFactory.buildCommand(GetUserDetailsRequest.from(username))
                .flatMap(Command::execute)
                .switchIfEmpty(Mono.error(() -> new ProfileNotFoundException(username)));
        return Mono.zip(details, loadAuthorities(username, cfg),
                        (response, roles) -> toProfile(response, roles, username, cfg))
                .doOnNext(profile -> eventPublisher.publishEvent(new ProfileLoadedEvent(profile)))
                .doOnNext(profile -> log.trace("Получен пользователь из {}: [{}]", profile.login(), username));
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
        var infos = response.getUserInfos();
        if (infos == null || username.isBlank()) {
            throw new ProfileNotFoundException(username);
        }
        List<TUserInfo> userInfos = infos.getUserInfo().stream().filter(Objects::nonNull).toList();
        if (userInfos.isEmpty()) {
            throw new ProfileNotFoundException(username);
        }
        for (TUserInfo info : userInfos) {
            var employee = info.getEmployee();
            if (employee == null) {
                throw new ProfileNotFoundException(username);
            }
            String reportedLogin = cfg.ossType() == OssConfig.OssType.USB
                    ? employee.getSamAccountName()
                    : employee.getLogin();
            if (!username.equals(reportedLogin)) {
                throw new ProfileNotFoundException(username);
            }
        }

        var employee = userInfos.getFirst().getEmployee();
        List<Organization> organizations = userInfos.stream().map(Organization::from).toList();
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
                username,
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
