package com.metricplatform.dbt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.gitsync.GitSnapshotReader;
import com.metricplatform.gitsync.GitSyncProperties;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 固定发布版本的可视化详情与 YAML 源码读取。 */
@Service
@RequiredArgsConstructor
public class DbtPresentationService {
    private final DbtCatalogService catalog;
    private final SemanticReleaseMapper releases;
    private final ObjectProvider<GitSnapshotReader> readers;
    private final GitSyncProperties git;
    private final DbtArtifactPresenter presenter = new DbtArtifactPresenter();
    private final ObjectMapper mapper = new ObjectMapper();

    public record SourceDocument(
            UUID releaseId, String buildSourceSha, String sourcePath, String content) {}

    public DbtArtifactPresenter.ResourcePresentation present(
            String projectId, UUID releaseId, String resourceId) {
        UUID fixed = catalog.resolvePublished(projectId, releaseId);
        DbtCatalogStore.Resource resource = catalog.resource(projectId, fixed, resourceId);
        SemanticReleaseEntity release = releases.selectById(fixed);
        return presenter.present(
                fixed,
                resourceId,
                resource.kind(),
                resource.name(),
                resource.definitionJson(),
                resource.queryable(),
                artifactSchemas(release));
    }

    public SourceDocument source(String projectId, UUID releaseId, String resourceId) {
        UUID fixed = catalog.resolvePublished(projectId, releaseId);
        DbtCatalogStore.Resource resource = catalog.resource(projectId, fixed, resourceId);
        SemanticReleaseEntity release = releases.selectById(fixed);
        if (resource.sourcePath() == null || !projectId.equals(git.getProjectId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "YAML 来源不存在");
        GitSnapshotReader reader = readers.getIfAvailable();
        if (reader == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Git 读取尚未启用");
        String content =
                reader.readYamlAtSha(
                        git.getProjectSubdir(), release.getBuildSourceSha(), resource.sourcePath());
        return new SourceDocument(
                fixed, release.getBuildSourceSha(), resource.sourcePath(), content);
    }

    private Map<String, String> artifactSchemas(SemanticReleaseEntity release) {
        if (release == null || release.getValidationJson() == null)
            throw new IllegalArgumentException("dbt 产物版本元数据缺失");
        try {
            JsonNode schemas = mapper.readTree(release.getValidationJson()).path("artifactSchemas");
            return Map.of(
                    "manifest", schemas.path("manifest").asText(),
                    "semanticManifest", schemas.path("semanticManifest").asText(),
                    "catalog", schemas.path("catalog").asText());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("dbt 产物版本元数据无效", error);
        }
    }
}
