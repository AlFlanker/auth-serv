package com.otr.portal.oss.userdetails.command;

import com.otr.portal.command.AbstractResilienceCommand;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.oss.connector.OssConnection;
import com.otr.portal.oss.exception.OssUnavailableException;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Mono;
import ru.otr.hr.ws.organization.v3.GetUserGroupsRequest;
import ru.otr.hr.ws.organization.v3.GetUserGroupsResponse;

import java.util.List;

import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.not;

/**
 * Команда получения групп/ролей пользователя (аналог монолитной {@code GetAuthoritiesCommand}).
 * Пустой ответ -> {@code ROLE_UNDEFINED}.
 */
@Slf4j
public class GetAuthoritiesCommand extends AbstractResilienceCommand<List<String>> {

    public static final String COMMAND_NAME = "OSS.userAuthorities";
    public static final String ROLE_UNDEFINED = "ROLE_UNDEFINED";

    private final String username;
    private final OssConnection ossConnection;

    @Builder
    GetAuthoritiesCommand(CommandResilienceDecorator resilienceDecorator,
                          CommandProperties commandProperties,
                          OssConnection ossConnection,
                          String username) {
        super(resilienceDecorator, commandProperties);
        requireNonNull(username, "Свойство username необходимо указать");
        requireNonNull(ossConnection, "Зависимость OssConnection необходимо указать");
        this.username = username;
        this.ossConnection = ossConnection;
    }

    @Override
    protected String getCommandName() {
        return COMMAND_NAME;
    }

    @Override
    protected Mono<List<String>> doExecute() {
        final GetUserGroupsRequest userGroupsRequest = new GetUserGroupsRequest();
        userGroupsRequest.setLogin(this.username);
        return ossConnection.getUserGroupsV1(userGroupsRequest)
                .map(GetUserGroupsResponse::getGroup)
                .doOnNext(this::logResponse)
                .filter(not(CollectionUtils::isEmpty))
                .switchIfEmpty(defaultResponse());
    }

    @Override
    protected Mono<List<String>> fallbackOnError(Throwable e) {
        log.error("Запрос к ОШС СОБИ не выполнен для логина {}", this.username, e);
        return Mono.error(new OssUnavailableException(
                String.format("Ошибка получения групп/ролей пользователя [%s] из ОШС", this.username), e));
    }

    private void logResponse(List<String> groups) {
        if (log.isTraceEnabled()) {
            log.trace("По запросу для пользователя {} был загружен список групп {}", this.username, groups);
        }
    }

    private static @NotNull Mono<List<String>> defaultResponse() {
        return Mono.just(List.of(ROLE_UNDEFINED));
    }
}
