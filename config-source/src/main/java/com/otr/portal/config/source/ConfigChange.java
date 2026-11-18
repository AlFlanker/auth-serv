package com.otr.portal.config.source;

/**
 * Нейтральное событие изменения записи источника данных.
 *
 * @param type  тип изменения
 * @param key   логический ключ (без внутреннего префикса хранилища)
 * @param value новое значение (UTF-8); {@code null} для {@link ConfigChangeType#DELETE}
 * @author alexraspopin
 */
public record ConfigChange(ConfigChangeType type, String key, String value) {
}
