package com.nova.studio.gallery;

import com.nova.studio.infra.HttpErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3.1 — {@link GalleryDataService} unit tests: seed-on-first-startup from the
 * legacy files (only when the table is empty), DB-first reads with file
 * fallback, and admin CRUD validation/delegation for prompts and blacklist.
 */
class GalleryDataServiceTest {

    @TempDir
    Path tempDir;

    private final PromptMapper promptMapper = mock(PromptMapper.class);
    private final BlacklistMapper blacklistMapper = mock(BlacklistMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path promptsFile;
    private Path blacklistFile;

    @BeforeEach
    void setUp() throws Exception {
        promptsFile = tempDir.resolve("prompts.json");
        blacklistFile = tempDir.resolve("blacklist.json");
    }

    private GalleryDataService newService() {
        return new GalleryDataService(promptMapper, blacklistMapper, objectMapper,
                promptsFile.toString(), blacklistFile.toString());
    }

    // ===== seed =====

    @Test
    void seedImportsPromptsWhenTableEmpty() throws Exception {
        Files.writeString(promptsFile, "[{\"title\":\"t1\",\"content\":\"c1\",\"type\":2}," +
                "{\"title\":\"t2\",\"content\":\"c2\"}]");
        when(promptMapper.selectCount(any())).thenReturn(0L);
        when(blacklistMapper.selectCount(any())).thenReturn(1L); // blacklist non-empty → skip
        newService().seedIfEmpty();
        verify(promptMapper, org.mockito.Mockito.atLeastOnce()).insert(any(PromptEntity.class));
    }

    @Test
    void seedSkipsWhenPromptsAlreadyPresent() throws Exception {
        Files.writeString(promptsFile, "[{\"title\":\"t1\",\"content\":\"c1\"}]");
        when(promptMapper.selectCount(any())).thenReturn(5L);
        newService().seedIfEmpty();
        verify(promptMapper, never()).insert(any(PromptEntity.class));
    }

    @Test
    void seedSkipsWhenPromptsFileMissing() {
        when(promptMapper.selectCount(any())).thenReturn(0L);
        newService().seedIfEmpty();
        verify(promptMapper, never()).insert(any(PromptEntity.class));
    }

    @Test
    void seedImportsBlacklistKeywords() throws Exception {
        Files.writeString(blacklistFile, "{\"keywords\":[\"色情\",\"暴力\",\"色情\"]}");
        when(promptMapper.selectCount(any())).thenReturn(1L); // prompts non-empty → skip
        when(blacklistMapper.selectCount(any())).thenReturn(0L);
        newService().seedIfEmpty();
        verify(blacklistMapper, org.mockito.Mockito.atLeastOnce()).insert(any(BlacklistEntity.class));
    }

    // ===== public reads (DB first, file fallback) =====

    @Test
    void promptsReadFromDb() {
        PromptEntity entity = new PromptEntity();
        entity.setId(UUID.randomUUID());
        entity.setTitle("DB 提示");
        entity.setContent("内容");
        entity.setType(2);
        entity.setEnabled(false);
        entity.setSortOrder(7);
        when(promptMapper.selectList(any())).thenReturn(List.of(entity));
        List<GalleryDataService.PromptRow> rows = newService().prompts();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).title()).isEqualTo("DB 提示");
        assertThat(rows.get(0).type()).isEqualTo(2);
        assertThat(rows.get(0).enabled()).isFalse();
        assertThat(rows.get(0).sortOrder()).isEqualTo(7);
    }

    @Test
    void promptsFallBackToFileWhenDbFails() throws Exception {
        Files.writeString(promptsFile, "[{\"title\":\"文件提示\",\"content\":\"c\",\"type\":1}]");
        when(promptMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        List<GalleryDataService.PromptRow> rows = newService().prompts();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).title()).isEqualTo("文件提示");
        assertThat(rows.get(0).id()).isNull();
    }

    @Test
    void blacklistKeywordsReadFromDb() {
        BlacklistEntity entity = new BlacklistEntity();
        entity.setId(UUID.randomUUID());
        entity.setKeyword("违禁");
        when(blacklistMapper.selectList(any())).thenReturn(List.of(entity));
        assertThat(newService().blacklistKeywords()).containsExactly("违禁");
    }

    @Test
    void blacklistFallBackToFileWhenDbFails() throws Exception {
        Files.writeString(blacklistFile, "{\"keywords\":[\"a\",\"b\"]}");
        when(blacklistMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        assertThat(newService().blacklistKeywords()).containsExactly("a", "b");
    }

    // ===== admin CRUD — prompts =====

    @Test
    void createPromptValidatesTitle() {
        assertThatThrownBy(() -> newService().createPrompt("  ", "内容", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createPromptAppliesDefaults() {
        PromptEntity captured = new PromptEntity();
        when(promptMapper.insert(any(PromptEntity.class))).thenAnswer(inv -> {
            PromptEntity e = inv.getArgument(0);
            captured.setId(e.getId());
            captured.setTitle(e.getTitle());
            captured.setContent(e.getContent());
            captured.setType(e.getType());
            captured.setEnabled(e.getEnabled());
            captured.setSortOrder(e.getSortOrder());
            return 1;
        });
        GalleryDataService.PromptRow row = newService().createPrompt("标题", "内容", 9, null, null);
        assertThat(row.type()).isEqualTo(1);      // out-of-range type clamped to default
        assertThat(row.enabled()).isTrue();        // default enabled
        assertThat(row.sortOrder()).isZero();
        assertThat(captured.getTitle()).isEqualTo("标题");
    }

    @Test
    void updatePromptNotFoundThrows404() {
        when(promptMapper.selectById(any(UUID.class))).thenReturn(null);
        assertThatThrownBy(() -> newService().updatePrompt(UUID.randomUUID(), "t", null, null, null, null))
                .isInstanceOf(HttpErrorException.class)
                .satisfies(e -> assertThat(((HttpErrorException) e).getStatusCode()).isEqualTo(404));
    }

    @Test
    void updatePromptPartialFields() {
        PromptEntity existing = new PromptEntity();
        existing.setId(UUID.randomUUID());
        existing.setTitle("原");
        existing.setContent("原内容");
        existing.setType(1);
        existing.setEnabled(true);
        when(promptMapper.selectById(existing.getId())).thenReturn(existing);
        GalleryDataService.PromptRow row = newService().updatePrompt(existing.getId(), null, null, null, false, null);
        assertThat(row.enabled()).isFalse();
        assertThat(row.title()).isEqualTo("原");
        verify(promptMapper).updateById(existing);
    }

    @Test
    void deletePromptNotFoundThrows404() {
        when(promptMapper.deleteById(any(UUID.class))).thenReturn(0);
        assertThatThrownBy(() -> newService().deletePrompt(UUID.randomUUID()))
                .isInstanceOf(HttpErrorException.class)
                .satisfies(e -> assertThat(((HttpErrorException) e).getStatusCode()).isEqualTo(404));
    }

    // ===== admin CRUD — blacklist =====

    @Test
    void addKeywordRejectsBlank() {
        assertThatThrownBy(() -> newService().addKeyword("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addKeywordRejectsDuplicate() {
        when(blacklistMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> newService().addKeyword("色情"))
                .isInstanceOf(HttpErrorException.class)
                .satisfies(e -> assertThat(((HttpErrorException) e).getStatusCode()).isEqualTo(409));
    }

    @Test
    void addKeywordInserts() {
        when(blacklistMapper.selectCount(any())).thenReturn(0L);
        when(blacklistMapper.insert(any(BlacklistEntity.class))).thenReturn(1);
        GalleryDataService.KeywordRow row = newService().addKeyword(" 色情 ");
        assertThat(row.keyword()).isEqualTo("色情");
    }

    @Test
    void deleteKeywordNotFoundThrows404() {
        when(blacklistMapper.delete(any())).thenReturn(0);
        assertThatThrownBy(() -> newService().deleteKeyword("色情"))
                .isInstanceOf(HttpErrorException.class)
                .satisfies(e -> assertThat(((HttpErrorException) e).getStatusCode()).isEqualTo(404));
    }
}
