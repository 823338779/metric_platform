package com.metricplatform.catalog;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.identity.auth.PlatformAuthenticationService;
import com.metricplatform.security.PlatformPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "metric.publication.mode=service-proxy")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublishedCatalogControllerTest {
    private static final String ROOT = "/api/v2/semantic-projects", TOKEN = "publication-token";
    @Autowired MockMvc mvc;
    @MockitoBean PublishedCatalogGateway gateway;
    @MockitoBean PlatformAuthenticationService authentication;
    @MockitoBean CatalogReadRepository historicalCatalog;

    @Test
    void forwardsBranchCatalogWithoutLocalPublicationOrMutations() throws Exception {
        UUID branch = UUID.randomUUID();
        UUID release = UUID.randomUUID();
        when(authentication.authenticate(TOKEN))
                .thenReturn(new PlatformPrincipal(UUID.randomUUID(), "test", "Test", null, 1));
        when(gateway.catalog("p", branch, release, "", null, 1, 50))
                .thenReturn(
                        new ObjectMapper()
                                .readTree(
                                        "{\"branchId\":\""
                                                + branch
                                                + "\",\"releaseId\":\""
                                                + release
                                                + "\",\"resources\":[]}"));
        var path = ROOT + "/p/branches/" + branch + "/releases/" + release + "/catalog";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchId").value(branch.toString()));
        mvc.perform(
                        post(ROOT + "/p/branches")
                                .header("Authorization", "Bearer " + TOKEN)
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(historicalCatalog);
        verify(gateway).catalog("p", branch, release, "", null, 1, 50);
    }

    @Test
    void protectsV2AndReadsWithoutLocalCatalog() throws Exception {
        when(gateway.projects()).thenReturn(new ObjectMapper().readTree("[]"));
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        verifyNoInteractions(gateway);
        when(authentication.authenticate(TOKEN))
                .thenReturn(new PlatformPrincipal(UUID.randomUUID(), "test", "Test", null, 1));
        mvc.perform(get(ROOT).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        verify(gateway).projects();
    }

    @Test
    void legacyCatalogUsesPublishedServiceAndDoesNotReadLocalMirror() throws Exception {
        UUID release = UUID.randomUUID();
        when(authentication.authenticate(TOKEN))
                .thenReturn(new PlatformPrincipal(UUID.randomUUID(), "test", "Test", null, 1));
        when(gateway.catalog("p", release, null, null, 1, 20))
                .thenReturn(
                        new ObjectMapper()
                                .readTree(
                                        "{\"releaseId\":\""
                                                + release
                                                + "\",\"page\":1,\"size\":20,\"total\":0,\"resources\":[]}"));
        mvc.perform(
                        get("/api/v1/semantic-projects/p/catalog")
                                .param("releaseId", release.toString())
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.releaseId").value(release.toString()));
        verifyNoInteractions(historicalCatalog);
    }

    @Test
    void legacyQueryUsesServiceWithoutLocalAdmission() throws Exception {
        UUID release = UUID.randomUUID();
        when(authentication.authenticate(TOKEN))
                .thenReturn(new PlatformPrincipal(UUID.randomUUID(), "test", "Test", null, 1));
        when(gateway.compatibility(eq("p"), eq("queries"), any()))
                .thenReturn(
                        new ObjectMapper()
                                .readTree(
                                        "{\"queryId\":\"Q\",\"releaseId\":\""
                                                + release
                                                + "\",\"status\":\"PENDING\"}"));
        mvc.perform(
                        post("/api/v1/semantic-projects/p/queries")
                                .contentType("application/json")
                                .content(
                                        "{\"releaseId\":\""
                                                + release
                                                + "\",\"metrics\":[\"revenue\"]}")
                                .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.queryId").value("Q"));
        verifyNoInteractions(historicalCatalog);
    }
}
