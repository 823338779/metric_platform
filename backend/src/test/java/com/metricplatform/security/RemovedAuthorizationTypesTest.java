package com.metricplatform.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RemovedAuthorizationTypesTest {
    @Test
    void authorizationAndTenancyTypesAreRemoved() {
        assertMissing("com.metricplatform.authorization.MetricAuthorizationService");
        assertMissing("com.metricplatform.authorization.QueryPermission");
        assertMissing("com.metricplatform.security.PlatformScopePolicy");
        assertMissing("com.metricplatform.identity.PlatformIdentityAdminController");
        assertMissing("com.metricplatform.identity.PlatformIdentityAdminService");
        assertMissing("com.metricplatform.persistence.entity.ApiClientEntity");
        assertMissing("com.metricplatform.persistence.entity.PlatformTenantEntity");
        assertMissing("com.metricplatform.persistence.entity.PlatformWorkspaceEntity");
        assertMissing("com.metricplatform.persistence.entity.PlatformUserWorkspaceEntity");
        assertMissing("com.metricplatform.persistence.entity.PlatformIdentityDatasetGrantEntity");
    }

    private static void assertMissing(String className) {
        assertThatThrownBy(() -> Class.forName(className))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
