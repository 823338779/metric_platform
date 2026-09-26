ALTER TABLE semantic_query DROP CONSTRAINT semantic_query_mode_check;
ALTER TABLE semantic_query ADD CONSTRAINT semantic_query_mode_check
    CHECK (mode IN ('QUERY','EXPLAIN','PREVIEW','DIMENSION_VALUES'));
COMMENT ON COLUMN semantic_query.mode IS '固定发布版本的指标查询、解释、预览或维度值查询模式';
