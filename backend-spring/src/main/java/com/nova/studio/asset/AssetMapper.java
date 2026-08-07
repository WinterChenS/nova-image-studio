package com.nova.studio.asset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * WIN-22 — MyBatis-Plus mapper for the {@code assets} table (entity
 * {@link AssetEntity}). The repository converts rows to the public
 * {@link AssetRepository.AssetRow} record.
 */
@Mapper
public interface AssetMapper extends BaseMapper<AssetEntity> {
}
