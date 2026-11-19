package com.metricplatform.persistence.store;

import com.metricplatform.catalog.AssetGrantRepository;
import com.metricplatform.catalog.BuildContracts.DeploymentKey;
import com.metricplatform.identity.TenantScope;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 仅存储授权身份和固定构建成员；当前发布事实由服务提供。 */
@Repository
@RequiredArgsConstructor
public class AssetGrantStore implements AssetGrantRepository {
    private final JdbcTemplate jdbc;

    private static final String BIND =
            """
            insert into asset_build_scope(tenant_id,build_id,environment_id,repository,branch_name)
            values(?,?,?,?,?) on conflict do nothing
            """;
    private static final String SUBMISSIONS =
            """
            insert into asset_build_scope(tenant_id,build_id,environment_id,repository,branch_name)
            select tenant_id,build_id,environment_id,command->>'repository',command->>'branchName'
            from environment_build_submission s where tenant_id=? and build_id is not null
            and not exists(select from asset_build_scope b where b.tenant_id=s.tenant_id and b.build_id=s.build_id)
            and command->>'repository' is not null and command->>'branchName' is not null on conflict do nothing
            """;
    private static final String UNBOUND =
            """
            select b.build_id,b.environment_id from build_ownership b left join asset_build_scope s
            using(tenant_id,build_id) where b.tenant_id=? and s.build_id is null
            """;
    private static final String TARGETS =
            """
            select distinct repository,environment_id,branch_name from asset_build_scope where tenant_id=?
            """;
    private static final String TARGET =
            """
            select repository,environment_id,branch_name from asset_build_scope where tenant_id=? and build_id=?
            """;
    private static final String READY =
            "select indexed from asset_build_scope where tenant_id=? and build_id=?";
    private static final String FINISH =
            "update asset_build_scope set indexed=true where tenant_id=? and build_id=?";
    private static final String MEMBER =
            """
            with a as (insert into deployed_asset(tenant_id,owner_id,environment_id,repository,branch_name,kind,node_id,dimension_name)
            select tenant_id,manager_id,?,?,?,?,?,? from platform_tenant where tenant_id=?
            on conflict(tenant_id,environment_id,repository,branch_name,kind,node_id,dimension_name)
            do update set owner_id=excluded.owner_id returning tenant_id,asset_id)
            insert into asset_build_member(tenant_id,asset_id,build_id,query_name,selector)
            select tenant_id,asset_id,?,?,? from a
            """;
    private static final String FIELDS =
            """
            select a.asset_id,a.owner_id,a.repository,a.environment_id,a.branch_name,a.kind,a.node_id,a.dimension_name,
            exists(select from asset_user_grant g where g.tenant_id=a.tenant_id and g.asset_id=a.asset_id and g.user_id=?),
            """;
    private static final String MEMBERS =
            FIELDS
                    + """
                    m.query_name,m.selector from deployed_asset a join asset_build_member m using(tenant_id,asset_id)
                    where a.tenant_id=? and m.build_id=? order by a.node_id,a.dimension_name
                    """;
    private static final String ASSETS =
            FIELDS
                    + """
                    a.node_id,'' from deployed_asset a where a.tenant_id=?
                    """;
    private static final RowMapper<Asset> ROW =
            (r, n) ->
                    new Asset(
                            r.getObject(1, UUID.class),
                            r.getObject(2, UUID.class),
                            new DeploymentKey(
                                    r.getString(3), r.getObject(4, UUID.class), r.getString(5)),
                            new Entry(
                                    r.getString(6),
                                    r.getString(7),
                                    r.getString(8),
                                    r.getString(10),
                                    r.getString(11)),
                            r.getBoolean(9));
    private static final RowMapper<DeploymentKey> KEY =
            (r, n) -> new DeploymentKey(r.getString(1), r.getObject(2, UUID.class), r.getString(3));
    private static final String TENANTS = "select distinct tenant_id from build_ownership";
    private static final String READERS =
            """
            select u.id,u.username from platform_user u join platform_tenant t using(tenant_id)
            where u.tenant_id=? and u.id<>t.manager_id order by u.username
            """;
    private static final String GRANT =
            "insert into asset_user_grant(tenant_id,user_id,asset_id) values(?,?,?) on conflict do"
                    + " nothing";
    private static final String REVOKE =
            "delete from asset_user_grant where tenant_id=? and user_id=? and asset_id=?";

    /** 调度器只枚举租户编号，各租户的资产读写必须绑定对应的作用域。 */
    public List<String> tenants() {
        return jdbc.queryForList(TENANTS, String.class);
    }

    public List<OwnedBuild> discover() {
        String tenant = TenantScope.require();
        jdbc.update(SUBMISSIONS, tenant);
        return jdbc.query(
                UNBOUND,
                (r, n) -> new OwnedBuild(r.getObject(1, UUID.class), r.getObject(2, UUID.class)),
                tenant);
    }

    public void bind(UUID build, DeploymentKey target) {
        jdbc.update(
                BIND,
                TenantScope.require(),
                build,
                target.environmentId(),
                target.repository(),
                target.branchName());
    }

    public List<DeploymentKey> targets() {
        return jdbc.query(TARGETS, KEY, TenantScope.require());
    }

    public DeploymentKey target(UUID build) {
        return jdbc.query(TARGET, KEY, TenantScope.require(), build).stream()
                .findFirst()
                .orElseThrow();
    }

    public boolean ready(UUID build) {
        return jdbc.queryForList(READY, Boolean.class, TenantScope.require(), build).contains(true);
    }

    /** 锁住固定构建后一次写入所有成员；回滚、并发重试及空目录均不会留下半份登记。 */
    @Transactional
    public void register(UUID build, DeploymentKey target, List<Entry> entries) {
        String tenant = TenantScope.require();
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(READY + " for update", Boolean.class, tenant, build))) return;
        if (!target.equals(target(build))) throw new IllegalStateException("构建目标不匹配");
        jdbc.batchUpdate(
                MEMBER,
                entries,
                200,
                (s, e) -> {
                    s.setObject(1, target.environmentId());
                    s.setString(2, target.repository());
                    s.setString(3, target.branchName());
                    s.setString(4, e.kind());
                    s.setString(5, e.nodeId());
                    s.setString(6, e.dimensionName());
                    s.setString(7, tenant);
                    s.setObject(8, build);
                    s.setString(9, e.queryName());
                    s.setString(10, e.selector());
                });
        jdbc.update(FINISH, tenant, build);
    }

    public List<Asset> members(UUID build, UUID reader) {
        return jdbc.query(MEMBERS, ROW, reader, TenantScope.require(), build);
    }

    private static final String GRANTED_ASSETS =
            ASSETS
                    + " and exists(select from asset_user_grant g where g.tenant_id=a.tenant_id and"
                    + " g.asset_id=a.asset_id and g.user_id=?) order by a.node_id,a.dimension_name";
    private static final String BY_ASSET = ASSETS + " and a.asset_id=?";
    private static final String HAS_READER =
            "select exists(select from platform_user u join platform_tenant t using(tenant_id)"
                + " where u.tenant_id=? and u.id=? and u.id<>t.manager_id)";

    public List<Asset> granted(UUID reader) {
        return jdbc.query(GRANTED_ASSETS, ROW, reader, TenantScope.require(), reader);
    }

    public Asset asset(UUID reader, UUID asset) {
        return jdbc.query(BY_ASSET, ROW, reader, TenantScope.require(), asset).stream()
                .findFirst()
                .orElseThrow();
    }

    public boolean hasReader(UUID reader) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(HAS_READER, Boolean.class, TenantScope.require(), reader));
    }

    public List<Account> readers() {
        return jdbc.query(
                READERS,
                (r, n) -> new Account(r.getObject(1, UUID.class), r.getString(2)),
                TenantScope.require());
    }

    public void grant(UUID reader, UUID asset) {
        jdbc.update(GRANT, TenantScope.require(), reader, asset);
    }

    public void revoke(UUID reader, UUID asset) {
        jdbc.update(REVOKE, TenantScope.require(), reader, asset);
    }
}
