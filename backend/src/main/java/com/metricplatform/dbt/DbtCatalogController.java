package com.metricplatform.dbt;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Git 托管目录只提供已发布版本的读取。 */
@RestController
@RequiredArgsConstructor
@RequestMapping(DbtCatalogController.ROOT)
public class DbtCatalogController {
    static final String ROOT = "/api/v1/semantic-projects/{projectId}";
    private static final String CATALOG = "/catalog",
            RESOURCE = "/resources/{resourceId}",
            LINEAGE = "/resources/{resourceId}/lineage",
            PRESENTATION = "/resources/{resourceId}/presentation",
            SOURCE = "/resources/{resourceId}/source";
    private final DbtCatalogService catalog;
    private final DbtPresentationService presentations;

    @GetMapping(CATALOG)
    public DbtCatalogService.CatalogPage catalog(
            @PathVariable String projectId,
            @RequestParam(required = false) UUID releaseId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return catalog.catalog(projectId, releaseId, q, kind, page, size);
    }

    @GetMapping(RESOURCE)
    public DbtCatalogStore.Resource resource(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestParam(required = false) UUID releaseId) {
        return catalog.resource(projectId, releaseId, resourceId);
    }

    @GetMapping(LINEAGE)
    public DbtCatalogService.Lineage lineage(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestParam(required = false) UUID releaseId,
            @RequestParam(defaultValue = "100") int limit) {
        return catalog.lineage(projectId, releaseId, resourceId, limit);
    }

    @GetMapping(PRESENTATION)
    public DbtArtifactPresenter.ResourcePresentation presentation(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestParam UUID releaseId) {
        return presentations.present(projectId, releaseId, resourceId);
    }

    @GetMapping(SOURCE)
    public DbtPresentationService.SourceDocument source(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestParam UUID releaseId) {
        return presentations.source(projectId, releaseId, resourceId);
    }
}
