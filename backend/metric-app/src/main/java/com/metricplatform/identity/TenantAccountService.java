package com.metricplatform.identity;

import com.metricplatform.identity.api.IdentityDtos;
import com.metricplatform.identity.api.IdentityDtos.AuthTokenRequest;
import com.metricplatform.security.PlatformPrincipal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class TenantAccountService {
    private static final String BCRYPT = "bcrypt", ACTIVE = "ACTIVE", SEQUENCE_EXHAUSTED = "2200H";
    private final PlatformIdentityRepository identities;
    private final PasswordEncoder encoder;
    private final Clock clock;

    /** 租户与管理者同时提交，登录名冲突时整个事务回滚。 */
    @Transactional
    public String provision(AuthTokenRequest input) {
        UUID manager = UUID.randomUUID();
        String tenant;
        try {
            tenant = identities.createTenant(manager);
        } catch (DataAccessException failure) {
            if (failure.getMostSpecificCause() instanceof SQLException sql
                    && SEQUENCE_EXHAUSTED.equals(sql.getSQLState()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "租户编号已耗尽");
            throw failure;
        }
        // 运维入口只可绑定数据库刚分配的租户，新账号与租户在同一事务提交。
        try (var scope = TenantScope.open(tenant)) {
            createAccount(input, manager, tenant);
        }
        return tenant;
    }

    /** 后端从会话确定新账号归属，客户端不能指定角色。 */
    @Transactional
    public IdentityDtos.IdentitySummary createReader(
            PlatformPrincipal actor, AuthTokenRequest input) {
        if (!actor.manager()) throw new AccessDeniedException("需要租户管理者");
        UUID id = UUID.randomUUID();
        createAccount(input, id, actor.tenantId());
        identities.writeAudit(
                actor.identityId().toString(), CREATE_READER, USER, id, SUCCEEDED, EMPTY_DETAILS);
        var reader = identities.findById(id);
        return new IdentityDtos.IdentitySummary(
                id,
                reader.username(),
                reader.displayName(),
                reader.authVersion(),
                actor.tenantId(),
                PlatformPrincipal.Role.READ_ONLY);
    }

    private static final String CREATE_READER = "TENANT_ACCOUNT_CREATED",
            USER = "platform_user",
            SUCCEEDED = "SUCCEEDED",
            EMPTY_DETAILS = "{}";

    private void createAccount(AuthTokenRequest input, UUID id, String tenant) {
        String username = input.username().trim().toLowerCase(Locale.ROOT);
        if (username.length() > 120
                || input.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("登录名或密码过长");
        try {
            identities.createUser(
                    new PlatformIdentityRepository.NewUser(
                            id,
                            username,
                            username,
                            encoder.encode(input.password()),
                            BCRYPT,
                            ACTIVE,
                            1,
                            clock.instant(),
                            tenant));
        } catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "登录名已被使用");
        }
    }
}
