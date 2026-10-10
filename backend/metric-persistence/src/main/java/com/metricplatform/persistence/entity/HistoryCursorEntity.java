package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

/** 服务事实的只读历史投影。 */
@Getter
@Setter
@TableName("history_cursor")
public class HistoryCursorEntity {
    /** 消费者唯一标识。 */
    @TableId(type = IdType.INPUT)
    private String consumer;

    /** 已原子提交水位。 */
    private String cursor;

    /** 同步成功时间。 */
    private java.time.OffsetDateTime syncedAt;
}
