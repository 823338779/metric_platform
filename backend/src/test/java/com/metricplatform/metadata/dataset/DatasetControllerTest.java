package com.metricplatform.metadata.dataset;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.identity.PlatformAuthenticationService;
import com.metricplatform.persistence.entity.DataSourceEntity;
import com.metricplatform.persistence.mapper.DataSourceMapper;
import com.metricplatform.persistence.mapper.DatasetMapper;
import com.metricplatform.security.PlatformPrincipal;
import java.util.List;
import java.util.Set;
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
class DatasetControllerTest {
    private static final UUID SOURCE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DatasetMapper datasets;
    @Autowired DataSourceMapper sources;
    @MockitoBean SourceMetadataInspector sourceMetadata;
    @MockitoBean PlatformAuthenticationService authentication;

    @BeforeEach
    void setUp() {
        datasets.delete(null);
        DataSourceEntity source =
                sources.selectAllIncludingDeleted().stream()
                        .filter(value -> SOURCE_ID.equals(value.getId()))
                        .findFirst()
                        .orElseGet(DataSourceEntity::new);
        source.setId(SOURCE_ID);
        source.setName("dataset-test-warehouse");
        source.setJdbcUrl("jdbc:mysql://starrocks:9030/dw");
        source.setDatabaseName("dw");
        source.setUsername("readonly");
        source.setSecretReference("STARROCKS_PASSWORD");
        source.setStatus("ENABLED");
        source.setDeleted(false);
        if (sources.selectAllIncludingDeleted().stream()
                .anyMatch(value -> SOURCE_ID.equals(value.getId()))) {
            sources.updateById(source);
        } else {
            sources.insert(source);
        }

        when(authentication.authenticate("admin-token"))
                .thenReturn(
                        new PlatformPrincipal(
                                UUID.randomUUID(),
                                "test-admin",
                                "Test Admin",
                                null,
                                1));
        when(authentication.authenticate("query-token"))
                .thenReturn(
                        new PlatformPrincipal(
                                UUID.randomUUID(),
                                "test-editor",
                                "Test Editor",
                                null,
                                1));

        when(sourceMetadata.inspectObject(eq(SOURCE_ID), eq("dw"), eq("fact_order")))
                .thenReturn(
                        new SourceMetadataInspector.SourceObjectDefinition(
                                "dw",
                                "fact_order",
                                "TABLE",
                                List.of(
                                        new SourceMetadataInspector.SourceColumn(
                                                "order_id", "BIGINT", 1),
                                        new SourceMetadataInspector.SourceColumn(
                                                "amount", "DECIMAL(18,2)", 2))));
    }

    @Test
    void createsListsUpdatesAndDeletesDatasetWithImpactPreview() throws Exception {
        String response =
                mvc.perform(
                                post("/api/v1/datasets")
                                        .header("Authorization", "Bearer admin-token")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                {"dataSourceId":"30000000-0000-0000-0000-000000000001",
                                 "name":"Sales orders","description":"Order facts",
                                 "schemaName":"dw","objectName":"fact_order",
                                 "selectedPhysicalFields":["order_id","amount"]}
                                """))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.name").value("Sales orders"))
                        .andExpect(jsonPath("$.objectType").value("TABLE"))
                        .andExpect(jsonPath("$.fields.length()").value(2))
                        .andExpect(jsonPath("$.version").value(0))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        JsonNode created = objectMapper.readTree(response);
        String datasetId = created.path("id").asText();
        String firstFieldId = created.path("fields").get(0).path("id").asText();

        mvc.perform(
                        get("/api/v1/datasets")
                                .header("Authorization", "Bearer query-token")
                                .param("q", "Sales")
                                .param("page", "0")
                                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(datasetId))
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(
                        patch("/api/v1/datasets/{id}", datasetId)
                                .header("Authorization", "Bearer query-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"name":"Sales order facts","description":"Updated",
                                 "version":0,"fieldUpdates":[
                                   {"fieldId":"%s","displayName":"Order ID","selected":true}
                                 ]}
                                """
                                                .formatted(firstFieldId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sales order facts"))
                .andExpect(jsonPath("$.fields[0].displayName").value("Order ID"))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(
                        get("/api/v1/datasets/{id}/deletion-impact", datasetId)
                                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldCount").value(2))
                .andExpect(jsonPath("$.metricCount").value(0))
                .andExpect(jsonPath("$.dimensionCount").value(0))
                .andExpect(jsonPath("$.relationshipCount").value(0));

        mvc.perform(
                        delete("/api/v1/datasets/{id}", datasetId)
                                .header("Authorization", "Bearer admin-token")
                                .param("cascade", "true"))
                .andExpect(status().isNoContent());

        mvc.perform(
                        get("/api/v1/datasets/{id}", datasetId)
                                .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsUnknownPhysicalFields() throws Exception {
        mvc.perform(
                        post("/api/v1/datasets")
                                .header("Authorization", "Bearer admin-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"dataSourceId":"30000000-0000-0000-0000-000000000001",
                                 "name":"Bad","schemaName":"dw","objectName":"fact_order",
                                 "selectedPhysicalFields":["invented_column"]}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }
}
