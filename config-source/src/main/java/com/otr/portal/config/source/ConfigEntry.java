package com.otr.portal.config.source;

/**
 * Нейтральная пара "ключ-значение" источника данных.
 * Не зависит от конкретного хранилища (jetcd и т.п.).
 *
 * @author alexraspopin
 */
public record ConfigEntry(String key, String value) {
}
