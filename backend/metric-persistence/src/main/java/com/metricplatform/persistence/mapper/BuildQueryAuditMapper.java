package com.metricplatform.persistence.mapper;

import com.metricplatform.query.QueryAuditRepository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

@Mapper
public interface BuildQueryAuditMapper extends QueryAuditRepository {
    @Override
    void record(
            @Param("execution") UUID execution,
            @Param("build") UUID build,
            @Param("user") UUID user);
}
