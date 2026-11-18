package com.otr.portal.identity.api.internal;

/**
 * Тело запроса внутреннего API с идентификатором пользователя (канонический логин).
 * Идентификатор передаётся в теле, а не в пути.
 *
 * @param login       канонический логин пользователя (для ЮСБ — samAccountName, как в монолите)
 * @param bypassCache {@code true} — загрузить профиль, минуя кэш сервиса (аналог
 *                    {@code loadUserByUsernameWithoutCache} монолита); по умолчанию {@code false}
 */
public record UserRequest(String login, Boolean bypassCache) {
}
