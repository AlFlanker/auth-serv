package com.otr.portal.oss.userdetails.command;

import com.otr.portal.command.Command;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.config.source.ConfigKey;
import com.otr.portal.config.source.ConfigSource;
import com.otr.portal.oss.config.OssConfig;
import com.otr.portal.oss.connector.OssConnection;
import com.otr.portal.oss.userdetails.command.model.GetAuthoritiesRequest;
import com.otr.portal.oss.userdetails.command.model.GetUserDetailsRequest;
import com.otr.portal.oss.userdetails.command.model.ManageUserCustomAttributesRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.otr.hr.ws.organization.v3.GetUserInfoResponse;
import ru.otr.hr.ws.organization.v3.SimpleResponse;

import java.util.List;

import static java.time.Duration.ofSeconds;

/**
 * Фабрика команд ОШС (аналог монолитного {@code OssCommandFactory}).
 * Таймауты/конкурентность берутся из актуальной конфигурации OssConfig (etcd).
 */
@Slf4j
@Component
public class OssCommandFactory {

    private final OssConnection ossConnection;
    private final ConfigSource configSource;
    private final ConfigKey<OssConfig> ossConfigKey;
    private final CommandResilienceDecorator resilienceDecorator;

    public OssCommandFactory(OssConnection ossConnection,
                             ConfigSource configSource,
                             ConfigKey<OssConfig> ossConfigKey,
                             CommandResilienceDecorator resilienceDecorator) {
        this.ossConnection = ossConnection;
        this.configSource = configSource;
        this.ossConfigKey = ossConfigKey;
        this.resilienceDecorator = resilienceDecorator;
    }

    public Mono<Command<GetUserInfoResponse>> buildCommand(GetUserDetailsRequest request) {
        log.trace("Создаёт команду получения информации о пользователе по логину \"{}\"", request.getUsername());
        return config().map(config -> GetUserDetailsCommand.builder()
                .resilienceDecorator(resilienceDecorator)
                .commandProperties(CommandProperties.builder()
                        .timeout(ofSeconds(config.userDetailsTimeout()))
                        .maxConcurrency(config.userDetailsMaxConcurrentRequests())
                        .build())
                .ossConnection(ossConnection)
                .username(request.getUsername())
                .build());
    }

    public Mono<Command<List<String>>> buildCommand(GetAuthoritiesRequest request) {
        log.trace("Создаём команду получения групп/ролей пользователя \"{}\"", request.getUsername());
        return config().map(config -> GetAuthoritiesCommand.builder()
                .resilienceDecorator(resilienceDecorator)
                .commandProperties(CommandProperties.builder()
                        .timeout(ofSeconds(config.authoritiesTimeout()))
                        .maxConcurrency(config.authoritiesMaxConcurrentRequests())
                        .build())
                .ossConnection(ossConnection)
                .username(request.getUsername())
                .build());
    }

    public Mono<Command<SimpleResponse>> buildCommand(ManageUserCustomAttributesRequest request) {
        log.trace("Создаём команду изменения кастомных атрибутов пользователя \"{}\"", request.getUsername());
        return config().map(config -> ManageUserCustomAttributesCommand.builder()
                .resilienceDecorator(resilienceDecorator)
                .commandProperties(CommandProperties.builder()
                        .timeout(ofSeconds(config.manageUserCustomAttributesTimeout()))
                        .maxConcurrency(config.manageUserCustomAttributesMaxConcurrentRequests())
                        .build())
                .ossConnection(ossConnection)
                .username(request.getUsername())
                .setAttributesMap(request.getSetAttributes())
                .sobiManageSecureKey(config.ossSecureKey())
                .build());
    }

    private Mono<OssConfig> config() {
        return configSource.get(ossConfigKey)
                .switchIfEmpty(Mono.error(new IllegalStateException("Конфигурация OSSConfig не найдена в etcd")));
    }
}
