package com.metricplatform.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metricplatform.api.ConflictException;
import com.metricplatform.identity.IdentityDtos.CreatePlatformUserRequest;
import com.metricplatform.identity.IdentityDtos.DatasetGrantRequest;
import com.metricplatform.identity.IdentityDtos.IdentitySummary;
import com.metricplatform.identity.IdentityDtos.ReplacePlatformPasswordRequest;
import com.metricplatform.identity.IdentityDtos.SetPlatformUserStatusRequest;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.PlatformIdentityDatasetGrantEntity;
import com.metricplatform.persistence.entity.PlatformTenantEntity;
import com.metricplatform.persistence.entity.PlatformUserEntity;
import com.metricplatform.persistence.entity.PlatformUserWorkspaceEntity;
import com.metricplatform.persistence.entity.PlatformWorkspaceEntity;
import com.metricplatform.persistence.service.DatasetEntityPersistenceService;
import com.metricplatform.persistence.service.PlatformIdentityDatasetGrantPersistenceService;
import com.metricplatform.persistence.service.PlatformTenantPersistenceService;
import com.metricplatform.persistence.service.PlatformUserPersistenceService;
import com.metricplatform.persistence.service.PlatformUserWorkspacePersistenceService;
import com.metricplatform.persistence.service.PlatformWorkspacePersistenceService;
import com.metricplatform.security.PlatformPrincipal;
import com.metricplatform.security.PlatformScopePolicy;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformIdentityAdminService {
    private static final Set<String> ROLES =
            Set.of("METRIC_QUERY_CLIENT", "SEMANTIC_EDITOR", "PLATFORM_ADMIN");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");

    private final PlatformTenantPersistenceService tenants;
    private final PlatformUserPersistenceService users;
    private final PlatformWorkspacePersistenceService workspaces;
    private final PlatformUserWorkspacePersistenceService userWorkspaces;
    private final PlatformIdentityDatasetGrantPersistenceService grants;
    private final DatasetEntityPersistenceService datasets;
    private final PasswordEncoder passwordEncoder;
    private final PlatformAuthenticationService authentication;
    private final PlatformIdentityAuditWriter audit;
    private final Clock clock;

    public PlatformIdentityAdminService(
            PlatformTenantPersistenceService tenants,
            PlatformUserPersistenceService users,
            PlatformWorkspacePersistenceService workspaces,
            PlatformUserWorkspacePersistenceService userWorkspaces,
            PlatformIdentityDatasetGrantPersistenceService grants,
            DatasetEntityPersistenceService datasets,
            PasswordEncoder passwordEncoder,
            PlatformAuthenticationService authentication,
            PlatformIdentityAuditWriter audit,
            Clock clock) {
        this.tenants = tenants;
        this.users = users;
        this.workspaces = workspaces;
        this.userWorkspaces = userWorkspaces;
        this.grants = grants;
        this.datasets = datasets;
        this.passwordEncoder = passwordEncoder;
        this.authentication = authentication;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public IdentitySummary createUser(CreatePlatformUserRequest request, PlatformPrincipal actor) {
        requireAdmin(actor);
        String role = request.roleCode().trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(role)) throw new IllegalArgumentException("Unsupported platform role");
        PlatformTenantEntity tenant = requireTenant(request.tenantKey());
        Set<UUID> workspaceIds = requireWorkspaces(tenant.getId(), request.workspaceIds());

        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(UUID.randomUUID());
        user.setTenantId(tenant.getId());
        user.setUsername(normalize(request.username()));
        user.setDisplayName(request.displayName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPasswordAlgorithm("bcrypt");
        user.setRoleCode(role);
        user.setStatus("ACTIVE");
        user.setAuthVersion(1);
        user.setPasswordChangedAt(clock.instant());
        try {
            users.save(user);
        } catch (DuplicateKeyException duplicate) {
            throw new ConflictException("Platform username already exists");
        }
        for (UUID workspaceId : workspaceIds) {
            PlatformUserWorkspaceEntity membership = new PlatformUserWorkspaceEntity();
            membership.setId(UUID.randomUUID());
            membership.setTenantId(tenant.getId());
            membership.setIdentityId(user.getId());
            membership.setWorkspaceId(workspaceId);
            userWorkspaces.save(membership);
        }
        audit.write(
                actor,
                "PLATFORM_IDENTITY_CREATED",
                "platform_user",
                user.getId(),
                "SUCCEEDED",
                Map.of("tenantId", tenant.getId(), "role", role));
        return summary(user, workspaceIds);
    }

    @Transactional
    public IdentitySummary replacePassword(
            UUID identityId, ReplacePlatformPasswordRequest request, PlatformPrincipal actor) {
        requireAdmin(actor);
        PlatformUserEntity user = requireUser(identityId);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPasswordAlgorithm("bcrypt");
        user.setPasswordChangedAt(clock.instant());
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setAuthVersion(user.getAuthVersion() + 1);
        users.updateById(user);
        authentication.revokeAll(user.getId());
        audit.write(
                actor,
                "PLATFORM_IDENTITY_PASSWORD_REPLACED",
                "platform_user",
                user.getId(),
                "SUCCEEDED",
                Map.of("authVersion", user.getAuthVersion()));
        return summary(user, workspaceIds(user));
    }

    @Transactional
    public IdentitySummary setStatus(
            UUID identityId, SetPlatformUserStatusRequest request, PlatformPrincipal actor) {
        requireAdmin(actor);
        String status = request.status().trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("Unsupported status");
        PlatformUserEntity user = requireUser(identityId);
        user.setStatus(status);
        user.setAuthVersion(user.getAuthVersion() + 1);
        users.updateById(user);
        authentication.revokeAll(user.getId());
        audit.write(
                actor,
                "PLATFORM_IDENTITY_STATUS_CHANGED",
                "platform_user",
                user.getId(),
                "SUCCEEDED",
                Map.of("status", status, "authVersion", user.getAuthVersion()));
        return summary(user, workspaceIds(user));
    }

    @Transactional
    public List<DatasetGrantRequest> replaceDatasetGrants(
            UUID identityId, List<DatasetGrantRequest> requested, PlatformPrincipal actor) {
        requireAdmin(actor);
        PlatformUserEntity user = requireUser(identityId);
        List<DatasetGrantRequest> normalized =
                List.copyOf(requested == null ? List.of() : requested);
        Set<UUID> uniqueIds =
                normalized.stream()
                        .map(DatasetGrantRequest::datasetId)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (uniqueIds.size() != normalized.size()) {
            throw new IllegalArgumentException("Dataset grants must not contain duplicates");
        }
        for (UUID datasetId : uniqueIds) {
            DatasetEntity dataset = datasets.getById(datasetId);
            if (dataset == null) throw new NoSuchElementException("Dataset not found");
        }

        grants.physicallyDeleteByIdentityId(user.getId());
        for (DatasetGrantRequest request : normalized) {
            PlatformIdentityDatasetGrantEntity grant = new PlatformIdentityDatasetGrantEntity();
            grant.setId(UUID.randomUUID());
            grant.setTenantId(user.getTenantId());
            grant.setIdentityId(user.getId());
            grant.setDatasetId(request.datasetId());
            grant.setDiscoverAllowed(request.discoverAllowed());
            grant.setQueryAllowed(request.queryAllowed());
            grants.save(grant);
        }
        user.setAuthVersion(user.getAuthVersion() + 1);
        users.updateById(user);
        authentication.revokeAll(user.getId());
        audit.write(
                actor,
                "PLATFORM_IDENTITY_DATASET_GRANTS_REPLACED",
                "platform_user",
                user.getId(),
                "SUCCEEDED",
                Map.of(
                        "datasetIds", uniqueIds.stream().map(UUID::toString).toList(),
                        "grantCount", normalized.size(),
                        "authVersion", user.getAuthVersion()));
        return normalized;
    }

    private PlatformTenantEntity requireTenant(String tenantKey) {
        PlatformTenantEntity tenant =
                tenants.getOne(
                        new LambdaQueryWrapper<PlatformTenantEntity>()
                                .eq(PlatformTenantEntity::getTenantKey, normalize(tenantKey)));
        if (tenant == null || !"ACTIVE".equals(tenant.getStatus())) {
            throw new NoSuchElementException("Platform tenant not found");
        }
        return tenant;
    }

    private PlatformUserEntity requireUser(UUID identityId) {
        PlatformUserEntity user = users.getById(identityId);
        if (user == null) throw new NoSuchElementException("Platform identity not found");
        return user;
    }

    private Set<UUID> requireWorkspaces(UUID tenantId, Set<UUID> requested) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        for (UUID workspaceId : requested) {
            PlatformWorkspaceEntity workspace = workspaces.getById(workspaceId);
            if (workspace == null
                    || !tenantId.equals(workspace.getTenantId())
                    || !"ACTIVE".equals(workspace.getStatus())) {
                throw new NoSuchElementException("Platform workspace not found");
            }
            result.add(workspaceId);
        }
        return Set.copyOf(result);
    }

    private Set<UUID> workspaceIds(PlatformUserEntity user) {
        return userWorkspaces
                .list(
                        new LambdaQueryWrapper<PlatformUserWorkspaceEntity>()
                                .eq(PlatformUserWorkspaceEntity::getTenantId, user.getTenantId())
                                .eq(PlatformUserWorkspaceEntity::getIdentityId, user.getId()))
                .stream()
                .map(PlatformUserWorkspaceEntity::getWorkspaceId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private IdentitySummary summary(PlatformUserEntity user, Set<UUID> workspaceIds) {
        Set<String> roles = Set.of(user.getRoleCode());
        return new IdentitySummary(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getTenantId(),
                workspaceIds,
                roles,
                PlatformScopePolicy.scopesFor(roles),
                user.getAuthVersion());
    }

    private static void requireAdmin(PlatformPrincipal actor) {
        if (actor == null || !actor.hasRole("PLATFORM_ADMIN")) {
            throw new AccessDeniedException("Platform administrator is required");
        }
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
