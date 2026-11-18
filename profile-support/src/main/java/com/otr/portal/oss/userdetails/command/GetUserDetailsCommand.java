package com.otr.portal.oss.userdetails.command;

import com.otr.portal.command.AbstractResilienceCommand;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.oss.connector.OssConnection;
import com.otr.portal.oss.exception.OssUnavailableException;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.otr.hr.ws.organization.v3.GetUserInfoRequest;
import ru.otr.hr.ws.organization.v3.GetUserInfoResponse;

import static java.util.Objects.requireNonNull;

/**
 * Команда получения информации о пользователе по логину (аналог монолитной {@code GetUserDetailsCommand}).
 */
@Slf4j
public class GetUserDetailsCommand extends AbstractResilienceCommand<GetUserInfoResponse> {

    public static final String COMMAND_NAME = "OSS.userDetailsByLogin";

    private final String username;
    private final OssConnection ossConnection;

    @Builder
    GetUserDetailsCommand(CommandResilienceDecorator resilienceDecorator,
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
    protected Mono<GetUserInfoResponse> doExecute() {
        final GetUserInfoRequest userRequest = new GetUserInfoRequest();
        userRequest.setLogin(this.username);
        return ossConnection.getUserInfoV1(userRequest)
                .doOnNext(ignored -> log.trace("Получен ответ источника на запрос профиля"));
    }

    @Override
    protected Mono<GetUserInfoResponse> fallbackOnError(Throwable e) {
        log.error("Ошибка получения пользователя {} из ОШС", this.username, e);
        return Mono.error(new OssUnavailableException(
                String.format("Ошибка получения пользователя [%s] из ОШС", this.username), e));
    }
}
