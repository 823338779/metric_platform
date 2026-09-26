package com.metricplatform.dbt;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 查看 Git 观测和候选状态，发布历史保留已清理版本的无凭据元数据。 */
@RestController
@RequiredArgsConstructor
@RequestMapping(DbtReleaseController.ROOT)
public class DbtReleaseController {
    static final String ROOT = "/api/v1/semantic-projects";
    private static final String RELEASES = "/{projectId}/releases",
            RELEASE = "/{projectId}/releases/{releaseId}";
    private final DbtCatalogService catalog;

    @GetMapping
    public List<DbtCatalogService.ProjectSummary> projects() {
        return catalog.projects();
    }

    @GetMapping(RELEASES)
    public List<DbtCatalogService.ReleaseSummary> releases(@PathVariable String projectId) {
        return catalog.releases(projectId);
    }

    @GetMapping(RELEASE)
    public DbtCatalogService.ReleaseSummary release(
            @PathVariable String projectId, @PathVariable UUID releaseId) {
        return catalog.release(projectId, releaseId);
    }
}
