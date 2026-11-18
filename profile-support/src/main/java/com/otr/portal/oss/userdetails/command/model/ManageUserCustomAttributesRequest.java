package com.otr.portal.oss.userdetails.command.model;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.util.Map;

/**
 * Параметры запроса команды изменения кастомных атрибутов пользователя.
 */
@Value
@Builder
public class ManageUserCustomAttributesRequest {
    @NonNull
    String username;
    Map<String, String> setAttributes;
}
