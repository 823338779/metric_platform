package com.metricplatform.persistence.identity;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.identity.PlatformIdentityRepository.Membership;
import com.metricplatform.persistence.entity.TenantEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.UUID;

/** 仅在身份存储内开放租户开通与认证前的归属定位。 */
@Mapper
interface IdentityTenantMapper extends BaseMapper<TenantEntity> {
    @InterceptorIgnore(tenantLine = "true")
    String createTenant(@Param("managerId") UUID managerId);

    @InterceptorIgnore(tenantLine = "true")
    String findLoginTenant(@Param("username") String username);

    @InterceptorIgnore(tenantLine = "true")
    String findLegacyTokenTenant(@Param("identityId") UUID identityId);

    @InterceptorIgnore(tenantLine = "true")
    String findTokenTenant(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    Membership membership(@Param("accountId") UUID accountId);
}
