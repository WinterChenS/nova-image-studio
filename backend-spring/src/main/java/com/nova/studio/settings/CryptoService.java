package com.nova.studio.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for model API keys (ADR-8 / F-15, T2.1).
 *
 * <p>Format: {@code v1:<iv-base64>:<tag+ciphertext-base64>}. The master key
 * comes from {@code NOVA_SETTINGS_SECRET_KEY} in the git-ignored {@code .env}
 * (base64, 32 bytes). GCM provides integrity via the auth tag, so a tampered
 * ciphertext fails decryption with a clean {@link IllegalStateException} rather
 * than garbage.
 *
 * <p>When the master key is missing at startup (fresh dev checkout), a random
 * ephemeral key is generated and a warning is logged: data encrypted during
 * that run cannot be decrypted after restart — production must set
 * {@code NOVA_SETTINGS_SECRET_KEY}.
 */
@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    private static final String VERSION = "v1";
    private static final int IV_LENGTH = 12;       // 96-bit GCM IV
    private static final int TAG_LENGTH_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec key;

    public CryptoService(@Value("${NOVA_SETTINGS_SECRET_KEY:}") String secretKeyBase64) {
        if (secretKeyBase64 == null || secretKeyBase64.isBlank()) {
            byte[] ephemeral = new byte[32];
            new SecureRandom().nextBytes(ephemeral);
            this.key = new SecretKeySpec(ephemeral, "AES");
            log.warn("[crypto] NOVA_SETTINGS_SECRET_KEY 未配置，已生成临时密钥；重启后已加密数据将无法解密，生产环境必须配置");
        } else {
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(secretKeyBase64.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("NOVA_SETTINGS_SECRET_KEY 不是合法的 base64", e);
            }
            if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
                throw new IllegalStateException("NOVA_SETTINGS_SECRET_KEY 长度必须为 16/24/32 字节（base64 解码后），当前 " + raw.length);
            }
            this.key = new SecretKeySpec(raw, "AES");
        }
    }

    /** Encrypt plaintext → {@code v1:iv:tag+cipher} (base64). */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION + ":" + Base64.getEncoder().encodeToString(iv)
                    + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    /** Decrypt {@code v1:iv:tag+cipher} back to plaintext. */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return null;
        }
        try {
            String[] parts = ciphertext.split(":", 3);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalStateException("未知的密文格式");
            }
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plain = cipher.doFinal(encrypted);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("API Key 解密失败（主密钥是否变更？）", e);
        }
    }

    /**
     * Mask an API key for API responses — {@code sk-***last4} (PRD §7 安全 /
     * issue acceptance: 接口返回脱敏). Short keys fall back to
     * {@code ***lastN}; empty stays empty.
     */
    public static String maskApiKey(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        String value = plaintext.trim();
        int len = value.length();
        if (len <= 4) {
            return "***" + value;
        }
        int keep = Math.min(4, len);
        String last = value.substring(len - keep);
        String prefix = len > 12 ? value.substring(0, Math.min(3, len - keep)) : "";
        return prefix + "***" + last;
    }
}
