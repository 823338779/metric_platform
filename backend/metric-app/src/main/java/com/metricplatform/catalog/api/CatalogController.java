package com.metricplatform.catalog.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.catalog.CatalogReadRepository;
import com.metricplatform.catalog.CatalogRepository;
import com.metricplatform.catalog.PresentationService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private static final ObjectMapper PRESENTATION_MAPPER = new ObjectMapper();

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
    public ResponseEntity<String> presentation(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestParam UUID releaseId)
            throws JsonProcessingException {
        // 产物使用 Jackson 2 JsonNode，须由同代序列化器写出，避免被 Jackson 3 当作 Bean 展开。
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        PRESENTATION_MAPPER.writeValueAsString(
                                presentations.present(projectId, releaseId, resourceId)));
    }

    @GetMapping(SOURCE)
    public PresentationService.SourceDocument source(
            @PathVariable String projectId,
            @PathVariable String resourceId,
            @RequestParam UUID releaseId) {
        return presentations.source(projectId, releaseId, resourceId);
    }
}
