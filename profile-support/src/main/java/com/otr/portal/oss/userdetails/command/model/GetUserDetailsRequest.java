package com.otr.portal.oss.userdetails.command.model;

import lombok.NonNull;
import lombok.Value;

/**
 * Параметры запроса команды получения информации о пользователе по логину.
 */
@Value
public class GetUserDetailsRequest {
    @NonNull
    String username;

    public static GetUserDetailsRequest from(String username) {
        return new GetUserDetailsRequest(username);
    }
}
