package com.metricplatform.query;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.metricplatform.identity.PlatformAuthenticationService;
import com.metricplatform.security.PlatformPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueryControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PlatformAuthenticationService authentication;

    @BeforeEach
    void authenticateTokens() {
        org.mockito.Mockito.when(authentication.authenticate("query-token"))
                .thenReturn(principal());
    }

    @Test
    void anonymousCannotListDatasets() throws Exception {
        mvc.perform(get("/api/v1/datasets")).andExpect(status().isUnauthorized());
    }

    @Test
    void queryClientCanListDatasets() throws Exception {
        mvc.perform(get("/api/v1/datasets").header("Authorization", "Bearer query-token"))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedAccountCanAdminister() throws Exception {
        mvc.perform(
                        post("/api/v1/data-sources")
                                .header("Authorization", "Bearer query-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedDatasetUuidIsBadRequest() throws Exception {
        mvc.perform(
                        get("/api/v1/datasets/not-a-uuid/semantic-model")
                                .header("Authorization", "Bearer query-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyMetricSelectionIsBadRequest() throws Exception {
        mvc.perform(
                        post("/api/v1/queries:validate")
                                .header("Authorization", "Bearer query-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"datasetId\":\"00000000-0000-0000-0000-000000000001\",\"metricIds\":[],\"dimensionIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    private static PlatformPrincipal principal() {
        return new PlatformPrincipal(
                UUID.randomUUID(),
                "test-user",
                "Test User",
                null,
                1);
    }
}
