package com.nova.studio.web;

import com.nova.studio.infra.RuntimeEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checklist #12 — gallery/config endpoints (Node parity): prompts/blacklist
 * file reads, config mode + password flag, verify sha256(password) check and
 * the no-password passthrough.
 */
class GalleryControllerTest {

    @TempDir
    Path tempDir;

    private final RuntimeEnv runtimeEnv = mock(RuntimeEnv.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private GalleryController newController() throws Exception {
        Path prompts = tempDir.resolve("prompts.json");
        Files.writeString(prompts, "[{\"title\":\"t\",\"content\":\"c\"}]");
        Path blacklist = tempDir.resolve("blacklist.json");
        Files.writeString(blacklist, "{\"keywords\":[\"bad\",\"worse\"]}");
        return new GalleryController(runtimeEnv, prompts.toString(), blacklist.toString(), mapper);
    }

    @Test
    void promptsReturnedFromFile() throws Exception {
        GalleryController controller = newController();
        assertThat(controller.prompts()).hasSize(1);
    }

    @Test
    void missingPromptsFileReturnsEmptyList() {
        GalleryController controller = new GalleryController(runtimeEnv,
                tempDir.resolve("nope.json").toString(), tempDir.resolve("nope2.json").toString(), mapper);
        assertThat(controller.prompts()).isEmpty();
        assertThat(controller.blacklist()).isEqualTo(Map.of("keywords", java.util.List.of()));
    }

    @Test
    void blacklistKeywordsReturned() throws Exception {
        GalleryController controller = newController();
        assertThat(controller.blacklist()).isEqualTo(Map.of("keywords", java.util.List.of("bad", "worse")));
    }

    @Test
    void configModeAndPasswordEnabled() {
        when(runtimeEnv.getString("PROMPT_GALLERY_MODE", "2")).thenReturn("1");
        when(runtimeEnv.getString("PROMPT_GALLERY_PASSWORD", "")).thenReturn("8848");
        GalleryController controller = new GalleryController(runtimeEnv,
                tempDir.resolve("p.json").toString(), tempDir.resolve("b.json").toString(), mapper);
        var body = controller.config().getBody();
        assertThat(body).containsEntry("promptGalleryMode", "1")
                .containsEntry("promptGalleryPasswordEnabled", true);
    }

    @Test
    void verifyChecksHashedPassword() {
        when(runtimeEnv.getString("PROMPT_GALLERY_PASSWORD", "")).thenReturn("8848");
        GalleryController controller = new GalleryController(runtimeEnv,
                tempDir.resolve("p.json").toString(), tempDir.resolve("b.json").toString(), mapper);
        assertThat(controller.verify(mapper.createObjectNode().put("password", "8848"))).isEqualTo(Map.of("ok", true));
        assertThat(controller.verify(mapper.createObjectNode().put("password", "wrong"))).isEqualTo(Map.of("ok", false));
    }

    @Test
    void verifyWithoutConfiguredPasswordAlwaysOk() {
        when(runtimeEnv.getString("PROMPT_GALLERY_PASSWORD", "")).thenReturn("");
        GalleryController controller = new GalleryController(runtimeEnv,
                tempDir.resolve("p.json").toString(), tempDir.resolve("b.json").toString(), mapper);
        assertThat(controller.verify(mapper.createObjectNode().put("password", "anything"))).isEqualTo(Map.of("ok", true));
    }
}
