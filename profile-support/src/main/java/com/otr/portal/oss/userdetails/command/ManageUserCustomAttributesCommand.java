package com.otr.portal.oss.userdetails.command;

import com.otr.portal.command.AbstractResilienceCommand;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.ResilienceFailures;
import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.oss.connector.OssConnection;
import com.otr.portal.oss.exception.OssUnavailableException;
import lombok.Builder;
import org.jetbrains.annotations.NotNull;
import reactor.core.publisher.Mono;
import ru.otr.hr.ws.organization.v3.ManageUserCustomAttributesRequest;
import ru.otr.hr.ws.organization.v3.MapItem;
import ru.otr.hr.ws.organization.v3.SimpleResponse;

import java.util.Map;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

/**
 * Команда изменения кастомных атрибутов пользователя (аналог монолитной {@code ManageUserCustomAttributesCommand}).
 */
public class ManageUserCustomAttributesCommand extends AbstractResilienceCommand<SimpleResponse> {

    public static final String COMMAND_NAME = "OSS.manageUserCustomAttributes";

    private final String username;
    private final OssConnection ossConnection;
    private final Map<String, String> setAttributesMap;
    private final String sobiManageSecureKey;

    @Builder
    ManageUserCustomAttributesCommand(CommandResilienceDecorator resilienceDecorator,
                                      CommandProperties commandProperties,
                                      OssConnection ossConnection,
                                      Map<String, String> setAttributesMap,
                                      String sobiManageSecureKey,
                                      String username) {
        super(resilienceDecorator, commandProperties);
        requireNonNull(username, "Свойство username необходимо указать");
        requireNonNull(ossConnection, "Зависимость OssConnection необходимо указать");
        requireNonNull(setAttributesMap, "Свойство setAttributesMap необходимо указать");
        this.ossConnection = ossConnection;
        this.setAttributesMap = setAttributesMap;
        this.sobiManageSecureKey = sobiManageSecureKey;
        this.username = username;
    }

    @Override
    protected String getCommandName() {
        return COMMAND_NAME;
    }

    @Override
    protected Mono<SimpleResponse> doExecute() {
        return Mono.defer(() -> ossConnection.manageUserCustomAttributesV1(prepareRequest()))
                .switchIfEmpty(Mono.error(() -> new OssUnavailableException(
                        "Источник не вернул подтверждение обновления атрибутов")));
    }

    @Override
    protected Mono<SimpleResponse> fallbackOnError(Throwable e) {
        if (e instanceof UnsupportedOperationException || e instanceof OssUnavailableException) {
            return Mono.error(e);
        }
        return switch (ResilienceFailures.classify(e)) {
            case TIMEOUT, CIRCUIT_OPEN, BULKHEAD_FULL -> Mono.error(e);
            default -> Mono.error(new OssUnavailableException("Ошибка обновления атрибутов в источнике профилей", e));
        };
    }

    private @NotNull ManageUserCustomAttributesRequest prepareRequest() {
        var request = new ManageUserCustomAttributesRequest();
        request.setManageSecure(this.sobiManageSecureKey);
        request.setLogin(this.username);
        var map = new ru.otr.hr.ws.organization.v3.Map();
        map.getItem().addAll(this.setAttributesMap.entrySet().stream()
                .map(ManageUserCustomAttributesCommand::mapToItem)
                .collect(toList()));
        request.setSetAttributes(map);
        return request;
    }

    private static @NotNull MapItem mapToItem(Map.Entry<String, String> entry) {
        MapItem item = new MapItem();
        item.setKey(entry.getKey());
        item.setValue(entry.getValue());
        return item;
    }

}
