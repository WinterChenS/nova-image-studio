package com.nova.studio.settings;

import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.infra.RuntimeEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2 T2.1 — model CRUD: AES-GCM key encryption at rest, masked responses,
 * key kept on masked update, protocol/type validation, per-user isolation and
 * unique-name conflicts.
 */
class ModelServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MODEL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String B64_KEY = "PttkTlCYSIm//Wgi+gWi9mWU9azNwKZmYWlqrF6cGMk=";

    private ModelRepository repository;
    private ModelService service;

    @BeforeEach
    void setUp() {
        repository = mock(ModelRepository.class);
        service = new ModelService(repository, new CryptoService(B64_KEY), MAPPER, mock(RuntimeEnv.class));
    }

    private ObjectNode imageModelBody(String apiKey) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("type", "image");
        body.put("protocol", "openai");
        body.put("name", "GPT Image 2");
        body.put("modelId", "gpt-image-2");
        body.put("baseUrl", "https://api.openai.com");
        body.put("builtinPreset", "gpt-image-2");
        body.put("maxRefImages", 4);
        body.put("maxOutputSize", "4K");
        body.put("supportsAdvancedParams", true);
        if (apiKey != null) {
            body.put("apiKey", apiKey);
        }
        return body;
    }

    private ModelRepository.ModelRow rowWith(String keyEnc, String caps) {
        return new ModelRepository.ModelRow(MODEL_ID, USER_ID, "image", "openai", "GPT Image 2",
                "gpt-image-2", "https://api.openai.com", keyEnc, caps, "gpt-image-2", null, null);
    }

    @Test
    void createEncryptsKeyAndReturnsMaskedDto() {
        String storedEnc = new CryptoService(B64_KEY).encrypt("sk-proj-abcdef1234567890");
        when(repository.existsName(USER_ID, "image", "GPT Image 2", null)).thenReturn(false);
        when(repository.insert(eq(USER_ID), eq("image"), eq("openai"), eq("GPT Image 2"), eq("gpt-image-2"),
                eq("https://api.openai.com"), any(), anyString(), eq("gpt-image-2"))).thenReturn(MODEL_ID);
        when(repository.findByIdAndUser(MODEL_ID, USER_ID)).thenReturn(Optional.of(
                rowWith(storedEnc, "{\"max_ref_images\":4,\"max_output_size\":\"4K\",\"supports_advanced_params\":true}")));

        var dto = service.create(USER_ID, imageModelBody("sk-proj-abcdef1234567890"));

        assertThat(dto).containsEntry("id", MODEL_ID.toString())
                .containsEntry("type", "image")
                .containsEntry("apiKey", "sk-***7890")
                .containsEntry("maxRefImages", 4);
        // ciphertext stored, never plaintext
        verify(repository).insert(eq(USER_ID), eq("image"), eq("openai"), eq("GPT Image 2"), eq("gpt-image-2"),
                eq("https://api.openai.com"), org.mockito.ArgumentMatchers.startsWith("v1:"), anyString(), eq("gpt-image-2"));
    }

    @Test
    void updateWithMaskedKeyKeepsStoredCiphertext() {
        String stored = new CryptoService(B64_KEY).encrypt("sk-original-9999");
        when(repository.findByIdAndUser(MODEL_ID, USER_ID)).thenReturn(Optional.of(rowWith(stored, "{}")));
        when(repository.existsName(USER_ID, "image", "GPT Image 2", MODEL_ID)).thenReturn(false);
        when(repository.findByIdAndUser(MODEL_ID, USER_ID)).thenReturn(Optional.of(rowWith(stored, "{}")));

        ObjectNode body = imageModelBody("sk-***9999");  // masked from the API list view
        var dto = service.update(USER_ID, MODEL_ID, body);

        verify(repository).update(eq(MODEL_ID), eq(USER_ID), eq("image"), eq("openai"), eq("GPT Image 2"),
                eq("gpt-image-2"), eq("https://api.openai.com"), eq(stored), anyString(), eq("gpt-image-2"));
        assertThat(dto).containsEntry("apiKey", "sk-***9999");
    }

    @Test
    void updateWithNewPlaintextKeyReEncrypts() {
        String stored = new CryptoService(B64_KEY).encrypt("sk-old-key");
        when(repository.findByIdAndUser(MODEL_ID, USER_ID)).thenReturn(Optional.of(rowWith(stored, "{}")));
        when(repository.existsName(USER_ID, "image", "GPT Image 2", MODEL_ID)).thenReturn(false);
        when(repository.findByIdAndUser(MODEL_ID, USER_ID)).thenReturn(Optional.of(rowWith("new-enc", "{}")));

        ObjectNode body = imageModelBody("sk-brand-new-key");
        service.update(USER_ID, MODEL_ID, body);

        verify(repository).update(eq(MODEL_ID), eq(USER_ID), eq("image"), eq("openai"), eq("GPT Image 2"),
                eq("gpt-image-2"), eq("https://api.openai.com"),
                org.mockito.ArgumentMatchers.startsWith("v1:"), anyString(), eq("gpt-image-2"));
    }

    @Test
    void rejectsInvalidProtocol() {
        ObjectNode body = imageModelBody("sk-x");
        body.put("protocol", "nope");
        assertThatThrownBy(() -> service.create(USER_ID, body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("协议类型无效");
    }

    @Test
    void rejectsDuplicateName() {
        when(repository.existsName(USER_ID, "image", "GPT Image 2", null)).thenReturn(true);
        assertThatThrownBy(() -> service.create(USER_ID, imageModelBody("sk-x")))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("MODEL_NAME_TAKEN");
                });
    }

    @Test
    void updateUnknownModelReturns404() {
        when(repository.findByIdAndUser(MODEL_ID, USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(USER_ID, MODEL_ID, imageModelBody("sk-x")))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(404));
    }

    @Test
    void resolveReturnsDecryptedKeyOnlyForOwner() {
        String stored = new CryptoService(B64_KEY).encrypt("sk-deep-secret");
        when(repository.findByIdAndUser(MODEL_ID, USER_ID)).thenReturn(Optional.of(
                new ModelRepository.ModelRow(MODEL_ID, USER_ID, "image", "openai", "GPT Image 2",
                        "gpt-image-2", "https://api.openai.com", stored, "{}", "gpt-image-2", null, null)));
        var resolved = service.resolve(USER_ID, MODEL_ID.toString());
        assertThat(resolved).isPresent();
        assertThat(resolved.get().apiKey()).isEqualTo("sk-deep-secret");

        UUID other = UUID.fromString("99999999-9999-9999-9999-999999999999");
        assertThat(service.resolve(other, MODEL_ID.toString())).isEmpty();
        assertThat(service.resolve(USER_ID, "not-a-uuid")).isEmpty();
    }

    @Test
    void deleteScopesByUser() {
        when(repository.delete(MODEL_ID, USER_ID)).thenReturn(true);
        service.delete(USER_ID, MODEL_ID);
        verify(repository).delete(MODEL_ID, USER_ID);
        when(repository.delete(MODEL_ID, USER_ID)).thenReturn(false);
        assertThatThrownBy(() -> service.delete(USER_ID, MODEL_ID))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(404));
    }
}
