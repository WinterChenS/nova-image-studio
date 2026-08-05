package com.nova.studio.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * WIN-16 (ADR-11) — MyBatis-Plus mapper for the {@code users} table (entity
 * {@link UserEntity}). The repository converts rows to the public
 * {@link UserRepository.UserRow} record.
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
