package com.metricplatform.metadata.relationship;

import static com.metricplatform.metadata.relationship.RelationshipDtos.Cardinality.MANY_TO_ONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.metricplatform.persistence.entity.DataSourceEntity;
import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.entity.DatasetFieldEntity;
import com.metricplatform.persistence.service.DataSourcePersistenceService;
import com.metricplatform.source.StarRocksDataSourceFactory;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RelationshipCardinalityValidatorTest {
    @Mock DataSourcePersistenceService sources;
    @Mock StarRocksDataSourceFactory connections;
    @Mock Connection connection;
    @Mock DatabaseMetaData metadata;
    @Mock Statement statement;
    @Mock ResultSet resultSet;

    @Test
    void checksTargetCompositeKeyWithServerDerivedQuotedIdentifiers() throws Exception {
        UUID sourceId = UUID.randomUUID();
        DataSourceEntity source = new DataSourceEntity();
        source.setId(sourceId);
        when(sources.getById(sourceId)).thenReturn(source);
        when(connections.openReadOnly(source)).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getIdentifierQuoteString()).thenReturn("`");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        RelationshipCardinalityValidator validator =
                new RelationshipCardinalityValidator(sources, connections);
        validator.validate(
                endpoint(dataset(sourceId, "dw", "orders"), "tenant_id", "customer_id"),
                endpoint(dataset(sourceId, "dw", "customer"), "tenant_id", "id"),
                MANY_TO_ONE);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).executeQuery(sql.capture());
        assertThat(sql.getValue())
                .isEqualTo(
                        "SELECT 1 FROM `dw`.`customer` WHERE `tenant_id` IS NOT NULL AND `id` IS NOT NULL GROUP BY `tenant_id`, `id` HAVING COUNT(*) > 1 LIMIT 1");
        verify(statement).setQueryTimeout(10);
    }

    @Test
    void rejectsADeclaredManyToOneWhenTargetKeyIsNotUnique() throws Exception {
        UUID sourceId = UUID.randomUUID();
        DataSourceEntity source = new DataSourceEntity();
        source.setId(sourceId);
        when(sources.getById(sourceId)).thenReturn(source);
        when(connections.openReadOnly(source)).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getIdentifierQuoteString()).thenReturn("`");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);

        RelationshipCardinalityValidator validator =
                new RelationshipCardinalityValidator(sources, connections);
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        endpoint(dataset(sourceId, "dw", "orders"), "customer_id"),
                                        endpoint(dataset(sourceId, "dw", "customer"), "id"),
                                        MANY_TO_ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target key is not unique");
    }

    private static RelationshipCardinalityValidator.Endpoint endpoint(
            DatasetEntity dataset, String... fields) {
        return new RelationshipCardinalityValidator.Endpoint(
                dataset,
                java.util.Arrays.stream(fields)
                        .map(RelationshipCardinalityValidatorTest::field)
                        .toList());
    }

    private static DatasetEntity dataset(UUID sourceId, String schema, String object) {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setDataSourceId(sourceId);
        dataset.setSchemaName(schema);
        dataset.setObjectName(object);
        return dataset;
    }

    private static DatasetFieldEntity field(String name) {
        DatasetFieldEntity field = new DatasetFieldEntity();
        field.setPhysicalName(name);
        return field;
    }
}
