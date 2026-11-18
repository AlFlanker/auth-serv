package com.otr.portal.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.regex.Pattern;

/** Неизменяемый набор ключей одного экземпляра сервиса. Материал загружается при старте. */
public class JwtKeyProvider {

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final RSAKey rsaKey;
    private final JWKSet fullJwkSet;
    private final JWKSet publicJwkSet;

    public JwtKeyProvider(TokenProperties properties, JwtKeyProperties keys) {
        String activeId = properties.keyId();
        validateKeyId(activeId);
        if (keys.verificationKeys().containsKey(activeId)) {
            throw new IllegalArgumentException("Активный kid повторяется в verification-keys");
        }
        try {
            rsaKey = keys.ephemeral()
                    ? new RSAKeyGenerator(3072).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                            .keyID(activeId).generate()
                    : readSigningKey(keys.privateKey(), activeId);
            fullJwkSet = new JWKSet(rsaKey);
            var publicKeys = new ArrayList<JWK>();
            publicKeys.add(rsaKey.toPublicJWK());
            var moduli = new HashSet<Base64URL>();
            moduli.add(rsaKey.getModulus());
            for (var entry : keys.verificationKeys().entrySet()) {
                validateKeyId(entry.getKey());
                byte[] der = readPem(entry.getValue(), "PUBLIC KEY");
                var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
                RSAKey jwk = publicKeyBuilder(key, entry.getKey()).build();
                if (!moduli.add(jwk.getModulus())) {
                    throw new IllegalArgumentException("Один RSA-ключ настроен с несколькими kid");
                }
                publicKeys.add(jwk);
            }
            publicJwkSet = new JWKSet(publicKeys);
        } catch (GeneralSecurityException | IOException | JOSEException e) {
            throw new IllegalStateException("Не удалось загрузить RSA-ключи для JWT", e);
        }
    }

    private static RSAKey readSigningKey(Path path, String kid) throws GeneralSecurityException, IOException {
        byte[] der = readPem(path, "PRIVATE KEY");
        try {
            var factory = KeyFactory.getInstance("RSA");
            var key = factory.generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(key instanceof RSAPrivateCrtKey privateKey)) {
                throw new IllegalArgumentException("Для подписи нужен приватный RSA CRT ключ PKCS#8");
            }
            var publicSpec = new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
            var publicKey = (RSAPublicKey) factory.generatePublic(publicSpec);
            return publicKeyBuilder(publicKey, kid).privateKey(privateKey).build();
        } finally {
            Arrays.fill(der, (byte) 0);
        }
    }

    private static RSAKey.Builder publicKeyBuilder(RSAPublicKey key, String kid) {
        if (key.getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException("Для RS256 нужен ключ длиной не менее 2048 бит");
        }
        return new RSAKey.Builder(key).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyID(kid);
    }

    private static byte[] readPem(Path path, String label) throws IOException {
        String pem = Files.readString(path, StandardCharsets.US_ASCII).strip();
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) {
            throw new IllegalArgumentException("Ожидается один PEM-блок " + label);
        }
        String encoded = pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", "");
        return Base64.getDecoder().decode(encoded);
    }

    private static void validateKeyId(String kid) {
        if (kid == null || !KEY_ID.matcher(kid).matches()) {
            throw new IllegalArgumentException("kid должен содержать от 1 до 128 символов A-Z, a-z, 0-9, '.', '_' или '-'");
        }
    }

    public RSAKey rsaKey() {
        return rsaKey;
    }

    /** Активный ключ с приватным материалом, только для энкодера. */
    public JWKSet fullJwkSet() {
        return fullJwkSet;
    }

    /** Публичные ключи для верификации и публикации в JWKS. */
    public JWKSet publicJwkSet() {
        return publicJwkSet;
    }
}
