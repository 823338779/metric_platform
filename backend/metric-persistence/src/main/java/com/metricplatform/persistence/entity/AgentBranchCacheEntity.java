package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.metricplatform.persistence.typehandler.JsonbTypeHandler;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName(value = "agent_branch_cache", autoResultMap = true)
public class AgentBranchCacheEntity extends BaseEntity {
    private String agentProjectId;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String responseJson;

    private Instant observedAt;
}
