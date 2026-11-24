package com.metricplatform.engine.warehouse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.metricplatform.ontology.WarehouseColumns.*;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.Test;

class StarRocksColumnReaderTest {
    @Test
    void requiresCompleteInventoryToProveAbsence() throws Exception {
        var access = mock(Access.class);
        when(access.url()).thenReturn("jdbc:mysql://fixture");
        when(access.username()).thenReturn("fixture");
        when(access.password()).thenReturn("fixture");
        when(access.complete()).thenReturn(true, false);
        var statement =
                StarRocksColumnReader.factory(access)
                        .getConfiguration()
                        .getMappedStatement(
                                StarRocksColumnReader.MetadataMapper.class.getName() + ".columns");
        var sql = statement.getBoundSql(Map.of("schema", "test", "table", "alias_%"));
        assertThat(sql.getSql())
                .isEqualTo(
                        "SELECT COLUMN_NAME, COLUMN_TYPE FROM information_schema.columns WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?");
        assertThat(sql.getParameterMappings())
                .extracting("property")
                .containsExactly("schema", "table");
        assertThat(statement.getTimeout()).isEqualTo(5);
        var factory = mock(SqlSessionFactory.class);
        var session = mock(SqlSession.class);
        var mapper = mock(StarRocksColumnReader.MetadataMapper.class);
        when(factory.openSession(true)).thenReturn(session);
        when(session.getMapper(StarRocksColumnReader.MetadataMapper.class)).thenReturn(mapper);
        var rows = List.of(new StarRocksColumnReader.ColumnType("amount", "decimal(18,2)"));
        when(mapper.columns("test", "alias_%"))
                .thenReturn(rows)
                .thenReturn(rows)
                .thenReturn(List.of())
                .thenThrow(new PersistenceException(new java.sql.SQLTimeoutException()));
        var target = new TableTarget("fixture", "default_catalog", "test", "alias_%");
        try (var factories = mockStatic(StarRocksColumnReader.class, CALLS_REAL_METHODS)) {
            factories.when(() -> StarRocksColumnReader.factory(access)).thenReturn(factory);
            var reader = new StarRocksColumnReader();
            var full = reader.read(target, access);
            assertThat(full.types()).containsEntry("amount", "decimal(18,2)");
            assertThat(full.complete()).isTrue();
            assertThat(reader.read(target, access).complete()).isFalse();
            assertThat(reader.read(target, access).reason()).isEqualTo("METADATA_INVISIBLE");
            assertThat(reader.read(target, access).reason()).isEqualTo("METADATA_TIMEOUT");
            verify(mapper, times(4)).columns("test", "alias_%");
            verify(session, times(4)).close();
        }
    }
}
