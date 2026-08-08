package com.nova.studio.history;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * WIN-39 (ADR-39) — MyBatis-Plus mapper for {@code histories}.
 */
@Mapper
public interface HistoryMapper extends BaseMapper<HistoryEntity> {
}
