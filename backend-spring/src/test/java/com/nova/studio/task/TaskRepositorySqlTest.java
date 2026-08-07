package com.nova.studio.task;

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
 * QA B-1/B-3 回归 — 任务列表/搜索 SQL 生成：
 * <ul>
 *   <li>B-1: {@code tasks.user_id} 为 UUID 列（V2），过滤条件必须渲染为
 *       {@code user_id = ?::uuid}（否则 PG 报 operator does not exist:
 *       uuid = character varying）；</li>
 *   <li>B-3: {@code selectCount} 的 wrapper 不得携带 ORDER BY（PG 聚合禁止）。</li>
 * </ul>
 */
class TaskRepositorySqlTest {

    private TaskMapper mapper;
    private TaskRepository repository;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TaskEntity.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(TaskMapper.class);
        repository = new TaskRepository(mapper);
    }

    private String whereSql(LambdaQueryWrapper<TaskEntity> wrapper) {
        return wrapper.getCustomSqlSegment();
    }

    @Test
    void countWrapperHasNoOrderBy() {
        when(mapper.selectCount(org.mockito.ArgumentMatchers.any()))
                .thenReturn(0L);
        when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        repository.searchByUser(UUID.randomUUID(), "p1", null, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<TaskEntity>> countCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectCount(countCaptor.capture());
        // B-3: COUNT 查询不得含 ORDER BY
        assertThat(whereSql(countCaptor.getValue()))
                .as("计数 wrapper 不应携带 ORDER BY")
                .doesNotContain("ORDER BY");

        ArgumentCaptor<LambdaQueryWrapper<TaskEntity>> listCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(listCaptor.capture());
        // 列表 wrapper 需排序 + LIMIT
        assertThat(whereSql(listCaptor.getValue())).contains("ORDER BY");
    }

    @Test
    void unclassifiedProjectRendersIsNull() {
        when(mapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(0L);
        when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        repository.searchByUser(UUID.randomUUID(), "__unclassified__", null, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<TaskEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectCount(captor.capture());
        assertThat(whereSql(captor.getValue())).contains("IS NULL");
    }

    @Test
    void taskFiltersBindUserIdAsUuidCast() {
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<>();
        TaskRepository.applyTaskFilters(wrapper, UUID.fromString("11111111-1111-1111-1111-111111111111"),
                null, "completed");
        String sql = whereSql(wrapper);
        // B-1: user_id 必须 ::uuid 绑定（UUID 列）
        assertThat(sql).contains("user_id");
        assertThat(sql).contains("::uuid");
        assertThat(sql).contains("status");
    }

    @Test
    void statusFilterAppliedWhenPresent() {
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<>();
        TaskRepository.applyTaskFilters(wrapper, UUID.randomUUID(), "p1", "completed");
        String sql = whereSql(wrapper);
        // 列级断言（值以 #{ew.paramNameValuePairs.*} 绑定，不内联）
        assertThat(sql).contains("project_id").contains("status").contains("::uuid");
    }
}
