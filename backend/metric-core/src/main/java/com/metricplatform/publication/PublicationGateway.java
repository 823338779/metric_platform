package com.metricplatform.publication;

import com.metricplatform.engine.EngineDtos.CatalogSnapshot;
import com.metricplatform.engine.EngineDtos.RunSnapshot;
import java.util.UUID;

/** 发布用例原子切换已验证候选的端口。 */
public interface PublicationGateway {
    boolean promote(
            UUID releaseId,
            ProjectRevision verifiedHead,
            RunSnapshot run,
            CatalogSnapshot catalog,
            String expectedSchemaName);
}
