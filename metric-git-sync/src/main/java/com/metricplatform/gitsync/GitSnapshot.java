package com.metricplatform.gitsync;

/** commit 与 YAML 字节来自同一个 Git 对象快照，消费方不能再读可变工作区。 */
public record GitSnapshot(String commit, byte[] yaml) {
    public GitSnapshot {
        yaml = yaml.clone();
    }

    @Override
    public byte[] yaml() {
        return yaml.clone();
    }
}
