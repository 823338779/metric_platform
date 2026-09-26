package com.metricplatform.dbt;

import com.metricplatform.dbt.DbtRuntimeDtos.CatalogSnapshot;
import com.metricplatform.dbt.DbtRuntimeDtos.RunSnapshot;
import java.util.UUID;

/** 发布用例原子切换已验证候选的端口。 */
public interface DbtPublicationGateway {
    boolean promote(UUID releaseId, DbtProjectRevision verifiedHead,
            RunSnapshot run, CatalogSnapshot catalog);
}
