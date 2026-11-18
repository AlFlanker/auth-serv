package com.otr.portal.identity.api.internal;

import java.util.Map;

/**
 * Тело запроса обновления кастомных атрибутов пользователя.
 *
 * @param login      канонический логин пользователя
 * @param attributes устанавливаемые значения кастомных полей
 */
public record UpdateCustomAttributesRequest(String login, Map<String, String> attributes) {
}
