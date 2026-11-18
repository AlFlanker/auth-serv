package com.otr.portal.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Map;

/** Пути к PEM-файлам, в том числе смонтированным из secret-manager. */
@ConfigurationProperties(prefix = "identity.token.keys")
public record JwtKeyProperties(Path privateKey, Map<String, Path> verificationKeys, boolean ephemeral) {

    public JwtKeyProperties {
        verificationKeys = verificationKeys == null ? Map.of() : Map.copyOf(verificationKeys);
        if (ephemeral && (privateKey != null || !verificationKeys.isEmpty())) {
            throw new IllegalArgumentException("Временный ключ несовместим с настроенными PEM-ключами");
        }
        if (!ephemeral && privateKey == null) {
            throw new IllegalArgumentException("Укажите PEM-файл в identity.token.keys.private-key");
        }
    }
}
