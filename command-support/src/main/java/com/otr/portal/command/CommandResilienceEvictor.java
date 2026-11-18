package com.otr.portal.command;

/**
 * Очищает лимитеры.
 *
 * @author alexraspopin
 * @since 14.07.2026
 */
public interface CommandResilienceEvictor {
    void evictByCommandPrefix(String prefix);
}
