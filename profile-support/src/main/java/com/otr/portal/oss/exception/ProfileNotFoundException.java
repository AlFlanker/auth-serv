package com.otr.portal.oss.exception;

/**
 * Пользователь не найден в источнике профилей (ОШС/ЮСБ).
 */
public class ProfileNotFoundException extends RuntimeException {

    public ProfileNotFoundException(String username) {
        super("Пользователь [" + username + "] не найден");
    }
}
