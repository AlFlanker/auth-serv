package com.otr.portal.oss.userdetails;

import com.otr.portal.oss.model.Profile;

/**
 * Событие успешной загрузки профиля пользователя из источника (аналог {@code UserLoadedEvent} монолита).
 */
public record ProfileLoadedEvent(Profile profile) {
}
