package com.metricplatform.catalog;

import java.util.List;
import java.util.UUID;

/** 已发布原生目录的持久化端口，页面卡片不在此落库。 */
public interface CatalogRepository {
    record Resource(
            String resourceId,
            String nativeId,
            String kind,
            String name,
            String description,
            String sourcePath,
            String definitionJson,
            boolean queryable) {}

    record Dependency(String upstreamResourceId, String downstreamResourceId, String kind) {}

    void replace(UUID releaseId, List<Resource> resources, List<Dependency> dependencies);

    List<Resource> resources(UUID releaseId);

    List<Dependency> dependencies(UUID releaseId);
}
