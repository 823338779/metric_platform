package com.metricplatform.metadata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.metricplatform.persistence.entity.DataSourceEntity;
import com.metricplatform.persistence.mapper.DataSourceMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MetadataControllerTest {
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    @Autowired MockMvc mvc;
    @Autowired DataSourceMapper sources;

    @BeforeEach
    void source() {
        if (sources.selectById(SOURCE_ID) != null) return;
        DataSourceEntity source = new DataSourceEntity();
        source.setId(SOURCE_ID);
        source.setName("warehouse");
        source.setJdbcUrl("jdbc:mysql://starrocks:9030/dw");
        source.setDatabaseName("dw");
        source.setUsername("readonly");
        source.setSecretReference("env");
        source.setStatus("ENABLED");
        sources.insert(source);
    }

    @Test
    void queryClientCanCreateDataset() throws Exception {
        mvc.perform(
                        post("/api/v1/admin/datasets")
                                .header("Authorization", "Bearer query-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"dataSourceId\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"query daily sales\",\"schemaName\":\"dw\",\"objectName\":\"query_daily_sales\",\"fields\":[{\"physicalName\":\"order_date\",\"displayName\":\"date\",\"starrocksType\":\"DATE\",\"dimensionEnabled\":true,\"metricEnabled\":false}]}"))
                .andExpect(status().isCreated());
    }

    @Test
    void administratorCreatesDatasetWithExplicitFields() throws Exception {
        mvc.perform(
                        post("/api/v1/admin/datasets")
                                .header("Authorization", "Bearer admin-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"dataSourceId\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"daily sales\",\"schemaName\":\"dw\",\"objectName\":\"daily_sales\",\"fields\":[{\"physicalName\":\"order_date\",\"displayName\":\"date\",\"starrocksType\":\"DATE\",\"dimensionEnabled\":true,\"metricEnabled\":false}]}"))
                .andExpect(status().isCreated());
    }
}
