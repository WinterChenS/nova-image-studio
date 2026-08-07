package com.nova.studio.asset;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA B-3 回归 — 素材列表/搜索 SQL 生成：
 * {@code selectCount} 的 wrapper 不得携带 ORDER BY（PG 报 column
 * "assets.created_at" must appear in the GROUP BY clause）；列表 wrapper
 * 需按 sort 排序 + LIMIT。
 */
class AssetRepositorySqlTest {

    private AssetMapper mapper;
    private AssetRepository repository;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AssetEntity.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AssetMapper.class);
        repository = new AssetRepository(mapper);
    }

    @Test
    void countWrapperHasNoOrderByForEverySort() {
        for (String sort : new String[]{"newest", "oldest", "used"}) {
            when(mapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(0L);
            when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

            repository.search(UUID.randomUUID(), "p1", "upload", "猫", "tag1", sort, 1, 48);

            ArgumentCaptor<LambdaQueryWrapper<AssetEntity>> countCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(mapper).selectCount(countCaptor.capture());
            assertThat(countCaptor.getValue().getCustomSqlSegment())
                    .as("sort=%s 时计数 wrapper 不应携带 ORDER BY", sort)
                    .doesNotContain("ORDER BY");

            ArgumentCaptor<LambdaQueryWrapper<AssetEntity>> listCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(mapper).selectList(listCaptor.capture());
            assertThat(listCaptor.getValue().getCustomSqlSegment())
                    .as("sort=%s 时列表 wrapper 需排序", sort)
                    .contains("ORDER BY");

            org.mockito.Mockito.clearInvocations(mapper);
        }
    }

    @Test
    void unclassifiedProjectRendersIsNull() {
        when(mapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(0L);
        when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        repository.search(UUID.randomUUID(), "__unclassified__", null, null, null, "newest", 1, 48);

        ArgumentCaptor<LambdaQueryWrapper<AssetEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectCount(captor.capture());
        assertThat(captor.getValue().getCustomSqlSegment()).contains("IS NULL");
    }

    @Test
    void tagFilterUsesJsonbContains() {
        LambdaQueryWrapper<AssetEntity> wrapper = new LambdaQueryWrapper<>();
        AssetRepository.applySearchFilters(wrapper, UUID.randomUUID(), "p1", null, null, "提示词");
        String sql = wrapper.getCustomSqlSegment();
        assertThat(sql).contains("tags");
        assertThat(sql).contains("?");
    }
}
