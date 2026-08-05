package com.nova.studio.settings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2 T2.1 — AES-GCM encrypt/decrypt roundtrip, tamper detection and key
 * masking (ADR-8 / F-15: Key 落库加密, 接口返回脱敏 sk-***last4).
 */
class CryptoServiceTest {

    private static final String B64_KEY = "PttkTlCYSIm//Wgi+gWi9mWU9azNwKZmYWlqrF6cGMk=";

    private final CryptoService crypto = new CryptoService(B64_KEY);

    @Test
    void encryptDecryptRoundtrip() {
        String cipher = crypto.encrypt("sk-proj-abcdef1234567890");
        assertThat(cipher).startsWith("v1:").doesNotContain("sk-proj");
        assertThat(crypto.decrypt(cipher)).isEqualTo("sk-proj-abcdef1234567890");
    }

    @Test
    void ciphertextIsRandomizedPerCall() {
        String a = crypto.encrypt("same-key");
        String b = crypto.encrypt("same-key");
        assertThat(a).isNotEqualTo(b);
        assertThat(crypto.decrypt(a)).isEqualTo(crypto.decrypt(b));
    }

    @Test
    void tamperedCiphertextFailsDecryption() {
        String cipher = crypto.encrypt("secret-key-value");
        char flipped = cipher.charAt(cipher.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = cipher.substring(0, cipher.length() - 1) + flipped;
        assertThatThrownBy(() -> crypto.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullAndEmptyStayNull() {
        assertThat(crypto.encrypt(null)).isNull();
        assertThat(crypto.encrypt("")).isNull();
        assertThat(crypto.decrypt(null)).isNull();
        assertThat(crypto.decrypt("")).isNull();
    }

    @Test
    void masksApiKeyWithLast4() {
        assertThat(CryptoService.maskApiKey("sk-proj-1234567890abcd")).isEqualTo("sk-***abcd");
        assertThat(CryptoService.maskApiKey("")).isEqualTo("");
        assertThat(CryptoService.maskApiKey("abc")).isEqualTo("***abc");
        assertThat(CryptoService.maskApiKey("abcdef")).isEqualTo("***cdef");
    }

    @Test
    void missingMasterKeyFallsBackToEphemeral() {
        CryptoService ephemeral = new CryptoService("");
        String cipher = ephemeral.encrypt("x");
        assertThat(ephemeral.decrypt(cipher)).isEqualTo("x");
    }

    @Test
    void rejectsInvalidKeyLength() {
        assertThatThrownBy(() -> new CryptoService(java.util.Base64.getEncoder().encodeToString(new byte[10])))
                .isInstanceOf(IllegalStateException.class);
    }
}
