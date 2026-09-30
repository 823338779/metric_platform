package com.metricplatform.publication.api;

import com.metricplatform.catalog.PublishedCatalogGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 切换后旧写入入口明确拒绝，避免无效刷新被误认为已受理发布。 */
@RestController
@ConditionalOnProperty(name = "metric.publication.mode", havingValue = "service-proxy")
public class PublicationOwnershipController {
    private static final String REFRESH = "/api/v1/semantic-projects:refresh";
    private static final String WEBHOOK = "/internal/git-sync/events";
    private static final String MANAGED = "publication_managed_by_service";

    @PostMapping({REFRESH, WEBHOOK})
    public void rejectPublication() {
        throw new PublishedCatalogGateway.Failure(409, MANAGED);
    }
}
