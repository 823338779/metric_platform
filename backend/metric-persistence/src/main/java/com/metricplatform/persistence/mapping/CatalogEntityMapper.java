package com.metricplatform.persistence.mapping;

import com.metricplatform.persistence.entity.SemanticDependencyEntity;
import com.metricplatform.persistence.entity.SemanticResourceEntity;
import com.metricplatform.persistence.store.CatalogStore;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** 重复字段映射交由 MapStruct 生成，复合主键的 releaseId 显式传入。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CatalogEntityMapper {
    @Mapping(target = "releaseId", source = "releaseId")
    @Mapping(target = "searchText", ignore = true)
    SemanticResourceEntity resource(UUID releaseId, CatalogStore.Resource resource);

    CatalogStore.Resource resource(SemanticResourceEntity entity);

    @Mapping(target = "releaseId", source = "releaseId")
    SemanticDependencyEntity dependency(UUID releaseId, CatalogStore.Dependency dependency);

    CatalogStore.Dependency dependency(SemanticDependencyEntity entity);
}
