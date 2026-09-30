package com.metricplatform.gitsync;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.metricplatform.identity.auth.PlatformAuthenticationService;
import com.metricplatform.security.PlatformPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GitRefreshControllerTest {
    private static final String PATH = "/api/v1/semantic-projects:refresh";
    private static final String TOKEN = "manual-refresh-token";
    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer ";
    private static final String USER = "test";
    @Autowired MockMvc mvc;
    @Autowired GitSyncProperties properties;
    @MockitoBean GitRefreshTrigger trigger;
    @MockitoBean PlatformAuthenticationService authentication;

    @BeforeEach
    void setup() {
        properties.setEnabled(true);
        when(authentication.authenticate(TOKEN))
                .thenReturn(new PlatformPrincipal(UUID.randomUUID(), USER, USER, null, 1));
    }

    @AfterEach
    void restoreConfiguration() {
        properties.setEnabled(false);
    }

    @Test
    void authenticatedRequestQueuesRefresh() throws Exception {
        mvc.perform(post(PATH).header(AUTHORIZATION, BEARER + TOKEN))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true));
        verify(trigger).requestRefresh();
    }

    @Test
    void anonymousRequestCannotTriggerRefresh() throws Exception {
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(trigger);
    }

    @Test
    void disabledAutomaticSynchronizationStillAcceptsManualRefresh() throws Exception {
        properties.setEnabled(false);
        mvc.perform(post(PATH).header(AUTHORIZATION, BEARER + TOKEN))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true));
        verify(trigger).requestRefresh();
    }
}
