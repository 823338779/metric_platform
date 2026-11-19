package com.metricplatform.environment;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.metricplatform.api.ApiExceptionHandler;
import com.metricplatform.environment.api.RepositoryController;
import com.metricplatform.identity.auth.PlatformAuthenticationService;
import com.metricplatform.security.PlatformPrincipal;
import com.metricplatform.security.PlatformSecurityConfig;
import com.metricplatform.security.PlatformTokenAuthenticationFilter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest(RepositoryController.class)
@Import({
    PlatformSecurityConfig.class,
    PlatformTokenAuthenticationFilter.class,
    ApiExceptionHandler.class
})
class RepositoryApiTest {
    private static final String PATH = "/api/dbt/repository/branches";
    private static final String REPOSITORY = "https://example.test/metrics.git";
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy security;
    private MockMvc mvc;
    @MockitoBean RepositorySource source;
    @MockitoBean PlatformAuthenticationService authentication;

    @BeforeEach
    void login() {
        // 仅注册完整安全链，避免 MVC 切片把令牌过滤器重复挂在安全链之前。
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        when(authentication.authenticate("repository-test"))
                .thenReturn(
                        new PlatformPrincipal(
                                UUID.randomUUID(),
                                "test",
                                "Test",
                                null,
                                1,
                                "tn_00001",
                                PlatformPrincipal.Role.TENANT_MANAGER));
        when(source.repository()).thenReturn(REPOSITORY);
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verify(source, never()).branches();
    }

    @Test
    void returnsConfiguredRepositoryAndActualBranchesIgnoringClientRemote() throws Exception {
        when(source.branches()).thenReturn(List.of("feature/report", "main"));
        mvc.perform(
                        get(PATH)
                                .param("repository", "https://other.test/repo.git")
                                .header("Authorization", "Bearer repository-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repository").value(REPOSITORY))
                .andExpect(jsonPath("$.branches[0]").value("feature/report"))
                .andExpect(jsonPath("$.branches[1]").value("main"));
    }

    @Test
    void unavailableRemoteHasSafeProblemResponse() throws Exception {
        when(source.branches()).thenThrow(new RepositorySource.UnavailableException());
        mvc.perform(get(PATH).header("Authorization", "Bearer repository-test"))
                .andExpect(status().isBadGateway())
                .andExpect(
                        jsonPath("$.detail")
                                .value(new RepositorySource.UnavailableException().getMessage()));
    }
}
