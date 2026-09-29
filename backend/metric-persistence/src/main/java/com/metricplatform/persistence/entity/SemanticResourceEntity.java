package com.metricplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.metricplatform.persistence.typehandler.JsonbTypeHandler;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** releaseId 与 resourceId 组成主键，按复合条件查询和删除。 */
@Getter
@Setter
@TableName(value = "semantic_resource", autoResultMap = true)
public class SemanticResourceEntity {
    /** 所属发布版本。 */
    private UUID releaseId;

    /** 跨版本稳定资源标识。 */
    private String resourceId;

    /** 引擎原生资源标识。 */
    private String nativeId;

    /** 资源类别。 */
    private String kind;

    /** 展示名称。 */
    private String name;

    /** 原生说明。 */
    private String description;

    /** 来源 Git 路径。 */
    private String sourcePath;

    /** 引擎原生定义 JSON。 */
    @TableField(value = "definition_json", typeHandler = JsonbTypeHandler.class)
    private String definitionJson;

    /** 此资源是否支持查询。 */
    private boolean queryable;

    /** 目录搜索文本，由数据库默认空值处理。 */
    @TableField(exist = false)
    private String searchText;
}
