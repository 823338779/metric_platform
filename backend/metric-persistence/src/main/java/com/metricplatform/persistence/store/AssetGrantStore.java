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
            with a as (insert into deployed_asset(tenant_id,owner_id,environment_id,kind,canonical_name,dimension_name)
            select tenant_id,manager_id,?,?,?,? from platform_tenant where tenant_id=?
            on conflict(tenant_id,environment_id,kind,canonical_name,dimension_name)
            do update set owner_id=excluded.owner_id returning tenant_id,asset_id)
            insert into asset_build_member(tenant_id,asset_id,build_id,query_name,selector,node_id)
            select tenant_id,asset_id,?,?,?,? from a
            """;
    // 授权身份独立于来源，目录和查询必须使用固定构建自己的原生标识与选择器。
    private static final String ASSET_FIELDS =
            """
            select a.asset_id,a.owner_id,b.repository,a.environment_id,b.branch_name,a.kind,m.node_id,a.dimension_name,
            (a.owner_id=u.id or g.user_id is not null),m.query_name,m.selector
            """;
    private static final String ASSET_JOIN =
            """
            from deployed_asset a join asset_build_member m using(tenant_id,asset_id)
            join asset_build_scope b on b.tenant_id=m.tenant_id and b.build_id=m.build_id
            join platform_user u on u.tenant_id=a.tenant_id and u.id=?
            left join asset_user_grant g on g.tenant_id=a.tenant_id and g.asset_id=a.asset_id and g.user_id=u.id
            """;
    private static final String MEMBERS =
            ASSET_FIELDS
                    + ASSET_JOIN
                    + " where a.tenant_id=? and m.build_id=? order by m.node_id,a.dimension_name";
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
            """
            with removed as (delete from asset_user_grant where tenant_id=? and user_id=? and asset_id=?)
            select exists(select from deployed_asset where tenant_id=? and asset_id=?)
            """;

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
                    s.setString(2, e.kind());
                    s.setString(3, e.queryName());
                    s.setString(4, e.dimensionName());
                    s.setString(5, tenant);
                    s.setObject(6, build);
                    s.setString(7, e.queryName());
                    s.setString(8, e.selector());
                    s.setString(9, e.nodeId());
                });
        jdbc.update(FINISH, tenant, build);
    }

    public List<Asset> members(UUID build, UUID reader) {
        return jdbc.query(MEMBERS, ROW, reader, TenantScope.require(), build);
    }

    private static final String NEXT_SCAN = "select nextval('asset_catalog_scan_seq')";
    private static final String OBSERVE =
            """
            insert into asset_catalog_observation(tenant_id,environment_id,build_id,scan_sequence)
            values(?,?,?,?) on conflict(tenant_id,environment_id)
            do update set build_id=excluded.build_id,scan_sequence=excluded.scan_sequence
            where asset_catalog_observation.scan_sequence < excluded.scan_sequence
            """;
    // 每个环境只展示当前观察到的构建；休眠资产继续保留用户授权。
    private static final String ACTIVE_JOIN =
            ASSET_JOIN
                    + """
                    join asset_catalog_observation o on o.tenant_id=b.tenant_id and o.environment_id=b.environment_id and o.build_id=b.build_id
                    """;
    private static final String LIST =
            ASSET_FIELDS
                    + ",m.build_id,coalesce(e.name,a.environment_id::text) "
                    + ACTIVE_JOIN
                    + """
                    left join execution_environment e on e.tenant_id=a.tenant_id and e.environment_id=a.environment_id
                    where a.tenant_id=?
                    order by coalesce(e.name,a.environment_id::text),a.environment_id,m.node_id,a.dimension_name
                    """;
    private static final String BY_ASSET =
            ASSET_FIELDS + ACTIVE_JOIN + " where a.tenant_id=? and a.asset_id=?";
    private static final String HAS_READER =
            "select exists(select from platform_user u join platform_tenant t using(tenant_id)"
                    + " where u.tenant_id=? and u.id=? and u.id<>t.manager_id)";

    public long nextScan() {
        return jdbc.queryForObject(NEXT_SCAN, Long.class);
    }

    public void observe(UUID environment, UUID build, long sequence) {
        jdbc.update(OBSERVE, TenantScope.require(), environment, build, sequence);
    }

    public List<CatalogItem> list(UUID reader) {
        return jdbc.query(
                LIST,
                (r, n) ->
                        new CatalogItem(
                                ROW.mapRow(r, n), r.getObject(12, UUID.class), r.getString(13)),
                reader,
                TenantScope.require());
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
        String tenant = TenantScope.require();
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(REVOKE, Boolean.class, tenant, reader, asset, tenant, asset)))
            throw new NoSuchElementException("资产不存在");
    }
}
