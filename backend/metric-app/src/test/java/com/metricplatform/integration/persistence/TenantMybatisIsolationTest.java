package com.metricplatform.integration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.metricplatform.identity.TenantAccountService;
import com.metricplatform.identity.TenantFixture;
import com.metricplatform.identity.TenantScope;
import com.metricplatform.identity.api.IdentityDtos.AuthTokenRequest;
import com.metricplatform.persistence.entity.PlatformUserEntity;
import com.metricplatform.persistence.mapper.PlatformUserMapper;
import com.metricplatform.persistence.mapper.SemanticProjectMapper;
import com.metricplatform.persistence.store.ReleaseStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TenantMybatisIsolationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantAccountService accounts;
    @Autowired PlatformUserMapper users;
    @Autowired SemanticProjectMapper projects;
    @Autowired ReleaseStore releases;

    @Test
    void auditedInsertCannotAssignOwnershipAndForeignIdCannotReadUpdateOrDelete() {
        TenantFixture.manager(jdbc);
        String other =
                accounts.provision(
                        new AuthTokenRequest(UUID.randomUUID().toString(), "fixture-password"));
        var user = new PlatformUserEntity();
        user.setId(UUID.randomUUID());
        user.setTenantId(other);
        user.setUsername(user.getId().toString());
        user.setDisplayName(user.getUsername());
        try (var scope = TenantScope.open(TenantFixture.ID)) {
            users.insert(user);
            assertThat(users.selectById(user.getId()).getTenantId()).isEqualTo(TenantFixture.ID);
        }
        try (var scope = TenantScope.open(other)) {
            assertThat(users.selectById(user.getId())).isNull();
            assertThat(users.updateById(user)).isZero();
            assertThat(users.softDeleteById(user.getId())).isZero();
        }
        assertThatThrownBy(() -> users.selectById(user.getId()))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void sameProjectNameHasIndependentBindingAndRowLockWithinEachTenant() {
        TenantFixture.manager(jdbc);
        String other =
                accounts.provision(
                        new AuthTokenRequest(UUID.randomUUID().toString(), "fixture-password"));
        String projectId = UUID.randomUUID().toString();
        try (var scope = TenantScope.open(TenantFixture.ID)) {
            releases.bindProject(projectId, "repo-one", ".", "profile");
        }
        try (var scope = TenantScope.open(other)) {
            assertThat(projects.lockProject(projectId)).isNull();
            releases.bindProject(projectId, "repo-two", ".", "profile");
            assertThat(projects.lockProject(projectId).getRepositoryId()).isEqualTo("repo-two");
            projects.deleteById(projectId);
        }
        try (var scope = TenantScope.open(TenantFixture.ID)) {
            var own = projects.selectById(projectId);
            assertThat(own.getTenantId()).isEqualTo(TenantFixture.ID);
            assertThat(own.getRepositoryId()).isEqualTo("repo-one");
        }
    }
}
