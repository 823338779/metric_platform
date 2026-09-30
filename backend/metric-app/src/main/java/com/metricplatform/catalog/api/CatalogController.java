package com.metricplatform.catalog.api;

import com.metricplatform.catalog.CatalogReadRepository;
import com.metricplatform.catalog.CatalogRepository;
import com.metricplatform.catalog.PresentationService;
import com.metricplatform.catalog.ResourcePresenter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Git 托管目录只提供已发布版本的读取。 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "metric.publication.mode",
        havingValue = "legacy",
        matchIfMissing = true)
@RequiredArgsConstructor
@RequestMapping(CatalogController.ROOT)
public class CatalogController {
    public static final String ROOT = "/api/v1/semantic-projects/{projectId}";
    private static final String CATALOG = "/catalog",
            RESOURCE = "/resources/{resourceId}",
            LINEAGE = "/resources/{resourceId}/lineage",
            PRESENTATION = "/resources/{resourceId}/presentation",
            SOURCE = "/resources/{resourceId}/source";
    private final CatalogReadRepository catalog;
    private final PresentationService presentations;

    @GetMapping(CATALOG)
    public CatalogReadRepository.CatalogPage catalog(
            @PathVariable String projectId,
            @RequestParam(required = false) UUID releaseId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return catalog.catalog(projectId, releaseId, q, kind, page, size);
    }

    @GetMapping(RESOURCE)
    public CatalogRepository.Resource resource(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestParam(required = false) UUID releaseId) {
        return catalog.resource(projectId, releaseId, resourceId);
    }

    @GetMapping(LINEAGE)
    public CatalogReadRepository.Lineage lineage(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestParam(required = false) UUID releaseId,
            @RequestParam(defaultValue = "100") int limit) {
        return catalog.lineage(projectId, releaseId, resourceId, limit);
    }

    @GetMapping(PRESENTATION)
    public ResourcePresenter.ResourcePresentation presentation(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestParam UUID releaseId) {
        return presentations.present(projectId, releaseId, resourceId);
    }

    @GetMapping(SOURCE)
    public PresentationService.SourceDocument source(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestParam UUID releaseId) {
        return presentations.source(projectId, releaseId, resourceId);
    }
}
