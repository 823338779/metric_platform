package com.metricplatform.ontology;

import java.util.Map;

/** 只读列目录端口。物理定位可持久化，连接配置仅供受信任的服务器端使用。 */
public interface WarehouseColumns {
    /** 快照核准的物理实例、内部 catalog、最终 schema 和表别名。 */
    record TableTarget(String instanceId, String catalog, String schema, String table) {}

    /** 容器内可达的受信目标和只读身份，complete 表示已验证整表元数据可见。 */
    interface Access {
        String url();
        String username();
        String password();
        boolean complete();
    }

    /** types 保留完整物理类型，reason 记录无法观察的原因。 */
    record TableInventory(Map<String, String> types, boolean complete, String observedAt, String reason) {
        public static TableInventory unavailable(String reason) {
            return new TableInventory(Map.of(), false, null, reason);
        }
    }
    TableInventory read(TableTarget target, Access access);
}
