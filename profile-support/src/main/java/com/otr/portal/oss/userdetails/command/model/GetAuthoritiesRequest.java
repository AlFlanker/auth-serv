package com.otr.portal.oss.userdetails.command.model;

import lombok.NonNull;
import lombok.Value;

/**
 * Параметры запроса команды получения групп/ролей пользователя.
 */
@Value
public class GetAuthoritiesRequest {
    @NonNull
    String username;

    public static GetAuthoritiesRequest from(String username) {
        return new GetAuthoritiesRequest(username);
    }
}
