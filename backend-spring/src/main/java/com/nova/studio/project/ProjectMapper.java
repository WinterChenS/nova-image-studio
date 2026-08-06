package com.nova.studio.project;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * WIN-22 — MyBatis-Plus mapper for the {@code projects} table (entity
 * {@link ProjectEntity}). The repository converts rows to the public
 * {@link ProjectRepository.ProjectRow} record.
 */
@Mapper
public interface ProjectMapper extends BaseMapper<ProjectEntity> {
}
