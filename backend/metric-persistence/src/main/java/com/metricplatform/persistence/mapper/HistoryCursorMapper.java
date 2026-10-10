package com.metricplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.persistence.entity.HistoryCursorEntity;

import org.apache.ibatis.annotations.*;

/** 游标行锁与 CAS 同时约束并行同步器。 */
@Mapper
public interface HistoryCursorMapper extends BaseMapper<HistoryCursorEntity> {
    String READ = "select * from history_cursor where consumer='build-history'";
    String LOCK = READ + " for update";
    String ADVANCE =
            "update history_cursor set cursor=#{next},synced_at=CURRENT_TIMESTAMP where"
                + " consumer='build-history' and cursor is not distinct from #{expected}";

    @Select(READ)
    HistoryCursorEntity read();

    @Select(LOCK)
    HistoryCursorEntity lock();

    @Update(ADVANCE)
    int advance(@Param("next") String next, @Param("expected") String expected);
}
