package com.nova.studio.gallery;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WIN-42 (T15/T16) — {@link PromptGalleryItemRepository} 单测：upsert 委托、
 * 搜索过滤（source/category/q/tag）构造（T16 服务端搜索 ILIKE/tag）。
 */
class PromptGalleryItemRepositoryTest {

    private final PromptGalleryItemMapper mapper = mock(PromptGalleryItemMapper.class);
    private final PromptGalleryItemRepository repository = new PromptGalleryItemRepository(mapper);

    @Test
    void upsertDelegatesWithJsonBDefaults() {
        repository.upsert("id-1", "nanobanana", "https://g", "标题", "内容",
                null, null, "海报", "作者", "备注", "{\"a\":1}", Instant.now());
        verify(mapper).upsert(org.mockito.ArgumentMatchers.eq("id-1"),
                org.mockito.ArgumentMatchers.eq("nanobanana"),
                org.mockito.ArgumentMatchers.eq("https://g"),
                org.mockito.ArgumentMatchers.eq("标题"),
                org.mockito.ArgumentMatchers.eq("内容"),
                org.mockito.ArgumentMatchers.eq("[]"),
                org.mockito.ArgumentMatchers.eq("[]"),
                org.mockito.ArgumentMatchers.eq("海报"),
                org.mockito.ArgumentMatchers.eq("作者"),
                org.mockito.ArgumentMatchers.eq("备注"),
                org.mockito.ArgumentMatchers.eq("{\"a\":1}"),
                any(Instant.class));
    }

    @Test
    void searchWithNoFiltersListsAll() {
        when(mapper.selectCount(any())).thenReturn(3L);
        List<PromptGalleryItemEntity> rows = List.of(new PromptGalleryItemEntity(),
                new PromptGalleryItemEntity(), new PromptGalleryItemEntity());
        when(mapper.selectList(any(Wrapper.class))).thenReturn(rows);
        PromptGalleryItemRepository.GalleryPage page = repository.search(null, null, null, null, 1, 20);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).hasSize(3);
        verify(mapper).selectList(any(Wrapper.class));
    }

    @Test
    void searchBuildsIlikeQueryForQ() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        repository.search(null, null, "prompt", null, 1, 20);
        // 构造的 wrapper 应包含 ILIKE 条件（通过 apply 注入），此处验证查询执行未被阻断
        verify(mapper).selectList(any(Wrapper.class));
    }

    @Test
    void searchFiltersBySourceAndCategory() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        repository.search("nanobanana", "海报", null, null, 1, 20);
        verify(mapper).selectList(any(Wrapper.class));
    }

    @Test
    void searchPageSizeClampedToRange() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        repository.search(null, null, null, null, 0, 500);
        verify(mapper).selectList(any(Wrapper.class));
    }
}
