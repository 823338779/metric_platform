# 固定的上游协议

`osi-0.1.1.schema.json` 原样来自 Apache Ossie，固定提交
`faf581054dcf7964d5fe0ceae7d6f415c8ce32a5` 的 `core-spec/osi-schema.json`。

上游来源：https://github.com/apache/ossie/blob/faf581054dcf7964d5fe0ceae7d6f415c8ce32a5/core-spec/osi-schema.json

保留上游 Apache 2.0 `LICENSE` 和 `NOTICE`。运行时不联网加载最新 Schema。
本项目只接受其中的 `ossie-metrics` Profile v1 子集；子集约束由 `OssieParser`
额外校验，自定义字段限定在 `COMMON` 扩展的 `data_agent` 命名空间内。
