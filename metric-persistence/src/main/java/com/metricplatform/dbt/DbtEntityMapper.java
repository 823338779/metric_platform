package com.metricplatform.dbt;

import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** 重复字段映射交由 MapStruct 生成，复合主键的 releaseId 显式传入。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DbtEntityMapper {
    @Mapping(target = "releaseId", source = "releaseId")
    @Mapping(target = "searchText", ignore = true)
    SemanticResourceEntity resource(UUID releaseId, DbtCatalogStore.Resource resource);

    DbtCatalogStore.Resource resource(SemanticResourceEntity entity);

    @Mapping(target = "releaseId", source = "releaseId")
    SemanticDependencyEntity dependency(UUID releaseId, DbtCatalogStore.Dependency dependency);

    DbtCatalogStore.Dependency dependency(SemanticDependencyEntity entity);
}
