package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.environment.EnvironmentContracts.EnvironmentInput;
import com.metricplatform.persistence.entity.ExecutionEnvironmentEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ExecutionEnvironmentMapper extends BaseMapper<ExecutionEnvironmentEntity> {
    record EnvironmentRow(UUID environmentId, String name, long configVersion, String execution) {}

    Long lockVersion(@Param("id") UUID id);

    void reserveNamespace(@Param("id") UUID id, @Param("input") EnvironmentInput input);

    boolean ownsNamespace(@Param("id") UUID id, @Param("input") EnvironmentInput input);

    void insertVersion(
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("execution") String execution);

    EnvironmentRow find(@Param("id") UUID id);

    List<EnvironmentRow> list();
}
