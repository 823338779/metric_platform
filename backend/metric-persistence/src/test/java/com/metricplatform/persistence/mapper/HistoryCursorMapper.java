package com.metricplatform.persistence.mapper;

import com.metricplatform.persistence.entity.HistoryCursorEntity;

import org.apache.ibatis.annotations.*;

/** 游标行锁与 CAS 同时约束并行同步器。 */
@Mapper
public interface HistoryCursorMapper extends HistoryCursorTableMapper {
    String INITIALIZE =
            "insert into history_cursor(consumer) values('build-history') on"
                    + " conflict(tenant_id,consumer) do nothing";
    String READ = "select * from history_cursor where not is_deleted and consumer='build-history'";
    String LOCK = READ + " for update";
    String ADVANCE =
            "update history_cursor set cursor=#{next},synced_at=CURRENT_TIMESTAMP where not"
                + " is_deleted and consumer='build-history' and cursor is not distinct from"
                + " #{expected}";

    @Select(READ)
    HistoryCursorEntity read();

    @Insert(INITIALIZE)
    int initialize();

    @Select(LOCK)
    HistoryCursorEntity lock();

    @Update(ADVANCE)
    int advance(@Param("next") String next, @Param("expected") String expected);
}
