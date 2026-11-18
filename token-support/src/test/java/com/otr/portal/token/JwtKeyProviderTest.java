package com.otr.portal.token;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeyProviderTest {

    private static final TokenProperties TOKEN =
            new TokenProperties(Duration.ofMinutes(10), "https://issuer.test", "active", null, null, null);

    private static KeyPair active;
    private static KeyPair previous;

    @TempDir
    Path directory;

    @BeforeAll
    static void generateRsaKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        active = generator.generateKeyPair();
        previous = generator.generateKeyPair();
    }

    @Test
    void anotherInstanceVerifiesSignaturesWithTheSameMountedKey() throws Exception {
        var privateFile = writePem("active.pem", "PRIVATE KEY", active.getPrivate().getEncoded());
        var keys = new JwtKeyProperties(privateFile, Map.of(), false);
        var first = new JwtKeyProvider(TOKEN, keys);
        var restarted = new JwtKeyProvider(TOKEN, keys);

        var signature = Signature.getInstance("SHA256withRSA");
        byte[] data = "identity-service".getBytes(StandardCharsets.UTF_8);
        signature.initSign(first.rsaKey().toRSAPrivateKey());
        signature.update(data);
        byte[] signed = signature.sign();

        var published = restarted.publicJwkSet().getKeyByKeyId("active").toRSAKey();
        signature.initVerify(published.toRSAPublicKey());
        signature.update(data);
        assertThat(signature.verify(signed)).isTrue();
        assertThat(published.toRSAPublicKey().getEncoded()).containsExactly(active.getPublic().getEncoded());
    }

    @Test
    void publishesActiveAndRetainedPublicKeysAndKeepsOnlyActiveKeyForSigning() throws Exception {
        var privateFile = writePem("active.pem", "PRIVATE KEY", active.getPrivate().getEncoded());
        var previousFile = writePem("previous.pub", "PUBLIC KEY", previous.getPublic().getEncoded());
        var keys = new JwtKeyProperties(privateFile, Map.of("previous", previousFile), false);
        var provider = new JwtKeyProvider(TOKEN, keys);

        assertThat(provider.fullJwkSet().getKeys()).singleElement().satisfies(key -> {
            assertThat(key.getKeyID()).isEqualTo("active");
            assertThat(key.isPrivate()).isTrue();
        });
        assertThat(provider.publicJwkSet().getKeys()).hasSize(2).allSatisfy(key -> {
            assertThat(key.isPrivate()).isFalse();
            assertThat(key.toJSONObject()).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi", "oth");
            assertThat(key.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
            assertThat(key.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        });
        assertThat(provider.publicJwkSet().getKeyByKeyId("previous").toRSAKey().toRSAPublicKey().getEncoded())
                .containsExactly(previous.getPublic().getEncoded());
    }

    @Test
    void missingOrInvalidSigningMaterialFailsClosed() throws Exception {
        assertThatThrownBy(() -> new JwtKeyProperties(null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        var missing = new JwtKeyProperties(directory.resolve("missing.pem"), null, false);
        assertThatThrownBy(() -> new JwtKeyProvider(TOKEN, missing)).isInstanceOf(IllegalStateException.class);

        var truncated = writePem("invalid.pem", "PRIVATE KEY", new byte[] {1, 2});
        assertThatThrownBy(() -> new JwtKeyProvider(TOKEN, new JwtKeyProperties(truncated, null, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsPrivateKeyInVerificationRingAndAmbiguousKeyIds() throws Exception {
        var privateFile = writePem("signer.pem", "PRIVATE KEY", active.getPrivate().getEncoded());
        var publicFile = writePem("signer.pub", "PUBLIC KEY", active.getPublic().getEncoded());
        var invalidRings = List.of(Map.of("old", privateFile), Map.of("active", publicFile), Map.of("alias", publicFile));
        for (var ring : invalidRings) {
            assertThatThrownBy(() -> new JwtKeyProvider(TOKEN, new JwtKeyProperties(privateFile, ring, false)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsWeakSigningAndVerificationKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        var weak = generator.generateKeyPair();
        var weakPrivate = writePem("weak.pem", "PRIVATE KEY", weak.getPrivate().getEncoded());
        var weakPublic = writePem("weak.pub", "PUBLIC KEY", weak.getPublic().getEncoded());
        var strongPrivate = writePem("strong.pem", "PRIVATE KEY", active.getPrivate().getEncoded());

        assertThatThrownBy(() -> new JwtKeyProvider(TOKEN, new JwtKeyProperties(weakPrivate, null, false)))
                .isInstanceOf(IllegalArgumentException.class);
        var weakVerification = new JwtKeyProperties(strongPrivate, Map.of("old", weakPublic), false);
        assertThatThrownBy(() -> new JwtKeyProvider(TOKEN, weakVerification))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ephemeralModeRequiresExplicitOptInAndCannotIgnoreConfiguredFiles() {
        var local = new JwtKeyProvider(TOKEN, new JwtKeyProperties(null, null, true));
        assertThat(local.rsaKey().isPrivate()).isTrue();
        assertThatThrownBy(() -> new JwtKeyProperties(directory.resolve("private.pem"), null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Path writePem(String fileName, String label, byte[] der) throws Exception {
        var encoder = Base64.getMimeEncoder(64, new byte[] {'\n'});
        String pem = "-----BEGIN %s-----\n%s\n-----END %s-----\n".formatted(label, encoder.encodeToString(der), label);
        return Files.writeString(directory.resolve(fileName), pem, StandardCharsets.US_ASCII);
    }
}
