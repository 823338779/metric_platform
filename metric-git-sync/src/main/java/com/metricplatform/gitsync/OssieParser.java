package com.metricplatform.gitsync;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.events.*;

/** 固定上游 Schema 后进一步校验项目子集，产出与存储及查询引擎无关的结构化定义。 */
public final class OssieParser {
    public static final int MAX_BYTES = 512 * 1024;
    private static final String ROOT = "/",
            NAME = "name",
            SOURCE = "source",
            DESCRIPTION = "description",
            FIELDS = "fields",
            MODEL = "semantic_model",
            DATASETS = "datasets",
            METRICS = "metrics",
            RELATIONSHIPS = "relationships",
            EXT = "custom_extensions",
            VENDOR = "vendor_name",
            DATA = "data",
            NAMESPACE = "data_agent",
            DISPLAY = "display_name",
            UNIT = "unit",
            DEFAULT_TIME = "default_time_field",
            PROFILE = "profile",
            PROFILE_VERSION = "profile_version",
            DIMENSION = "dimension",
            IS_TIME = "is_time",
            EXPRESSION = "expression",
            DIALECTS = "dialects",
            DIALECT = "dialect",
            AI = "ai_context",
            SYNONYMS = "synonyms",
            PRIMARY_KEY = "primary_key",
            FROM = "from",
            TO = "to",
            FROM_COLUMNS = "from_columns",
            TO_COLUMNS = "to_columns",
            JOIN = "join_type",
            VERSION = "version",
            ANSI = "ANSI_SQL",
            COMMON = "COMMON",
            PROFILE_NAME = "ossie-metrics",
            LEFT = "LEFT",
            INNER = "INNER",
            MERGE = "<<";
    private static final String IDENTIFIER = "[a-z][a-z0-9_]*";
    private static final String SCHEMA = "/ossie/osi-0.1.1.schema.json";
    private final ObjectMapper yaml =
            new ObjectMapper(
                            YAMLFactory.builder()
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(64)
                                                    .maxStringLength(MAX_BYTES)
                                                    .build())
                                    .build())
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final ObjectMapper json =
            new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final JsonSchema schema;

    public OssieParser() {
        try (var input = OssieParser.class.getResourceAsStream(SCHEMA)) {
            schema =
                    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(input);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取内置 Ossie Schema", e);
        }
    }

    public OssieModel parse(byte[] bytes) {
        try {
            require(bytes.length > 0 && bytes.length <= MAX_BYTES, ROOT, "协议文件大小不合法");
            String text = new String(bytes, StandardCharsets.UTF_8);
            // 事件预检阻断别名、锚点和显式标签，避免同一 YAML 在不同实现中被解释成不同文档。
            for (var event : new Yaml().parse(new StringReader(text))) {
                if (event instanceof AliasEvent
                        || event instanceof NodeEvent node && node.getAnchor() != null
                        || event instanceof ScalarEvent scalar
                                && (scalar.getTag() != null || MERGE.equals(scalar.getValue()))
                        || event instanceof CollectionStartEvent start && start.getTag() != null)
                    throw error(ROOT, "不允许 YAML 锚点、标签或合并键");
            }
            JsonNode root;
            try (var parser = yaml.createParser(bytes)) {
                root = yaml.readTree(parser);
                require(parser.nextToken() == null, ROOT, "只允许一个 YAML 文档");
            }
            var errors = schema.validate(root);
            require(errors.isEmpty(), ROOT, "文档不符合固定的 Ossie 0.1.1 Schema");
            keys(root, ROOT, VERSION, MODEL);
            var models = array(root, MODEL, ROOT, true);
            require(models.size() == 1, ROOT, "只允许一个语义模型");
            JsonNode m = models.getFirst();
            String path = ROOT + MODEL + "/0";
            keys(m, path, NAME, DESCRIPTION, DATASETS, METRICS, RELATIONSHIPS, EXT);
            var ext = extension(m, path, PROFILE, PROFILE_VERSION);
            require(
                    PROFILE_NAME.equals(string(ext, PROFILE, path, true))
                            && ext.path(PROFILE_VERSION).isIntegralNumber()
                            && java.math.BigInteger.ONE.equals(
                                    ext.path(PROFILE_VERSION).bigIntegerValue()),
                    path,
                    "不支持的 Profile 版本");
            List<OssieModel.Dataset> datasets = new ArrayList<>();
            for (var d : array(m, DATASETS, path, true))
                datasets.add(dataset(d, path + ROOT + DATASETS + ROOT + datasets.size()));
            require(!datasets.isEmpty(), path, "数据集不能为空");
            List<OssieModel.Metric> metrics = new ArrayList<>();
            for (var v : array(m, METRICS, path, false))
                metrics.add(metric(v, path + ROOT + METRICS + ROOT + metrics.size()));
            List<OssieModel.Relationship> relationships = new ArrayList<>();
            for (var v : array(m, RELATIONSHIPS, path, false))
                relationships.add(
                        relationship(v, path + ROOT + RELATIONSHIPS + ROOT + relationships.size()));
            var result =
                    new OssieModel(
                            id(m, NAME, path),
                            List.copyOf(datasets),
                            List.copyOf(metrics),
                            List.copyOf(relationships),
                            string(m, DESCRIPTION, path, false));
            references(result);
            return result;
        } catch (ProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw error(ROOT, "YAML 或扩展 JSON 无法解析");
        }
    }

    private OssieModel.Dataset dataset(JsonNode d, String path) throws Exception {
        keys(d, path, NAME, SOURCE, DESCRIPTION, FIELDS, PRIMARY_KEY, EXT, AI);
        var ext = extension(d, path, DISPLAY, DEFAULT_TIME);
        String name = id(d, NAME, path);
        List<OssieModel.Field> fields = new ArrayList<>();
        for (var f : array(d, FIELDS, path, true)) {
            String fp = path + ROOT + FIELDS + ROOT + fields.size();
            keys(f, fp, NAME, EXPRESSION, DIMENSION, DESCRIPTION, AI, EXT);
            var fe = extension(f, fp, DISPLAY, UNIT);
            String fieldName = id(f, NAME, fp);
            String column = expression(f, fp);
            require(column.matches(IDENTIFIER), fp, "字段只允许简单物理列映射");
            OssieModel.Role role = OssieModel.Role.MEASURE;
            if (f.has(DIMENSION)) {
                keys(f.get(DIMENSION), fp, IS_TIME);
                require(f.get(DIMENSION).path(IS_TIME).isBoolean(), fp, "维度必须声明 is_time");
                role =
                        f.get(DIMENSION).get(IS_TIME).booleanValue()
                                ? OssieModel.Role.TIME
                                : OssieModel.Role.DIMENSION;
            }
            fields.add(
                    new OssieModel.Field(
                            fieldName,
                            column,
                            string(f, DESCRIPTION, fp, false),
                            display(fe, fieldName, fp),
                            role,
                            string(fe, UNIT, fp, false),
                            synonyms(f, fp)));
        }
        require(!fields.isEmpty(), path, "字段不能为空");
        var primary = strings(d, PRIMARY_KEY, path);
        require(primary.size() <= 1, path, "只支持单列主键");
        return new OssieModel.Dataset(
                name,
                id(d, SOURCE, path),
                string(d, DESCRIPTION, path, false),
                display(ext, name, path),
                string(ext, DEFAULT_TIME, path, false),
                primary,
                synonyms(d, path),
                List.copyOf(fields));
    }

    private OssieModel.Metric metric(JsonNode m, String path) throws Exception {
        keys(m, path, NAME, EXPRESSION, DESCRIPTION, AI, EXT);
        var ext = extension(m, path, DISPLAY, UNIT);
        String name = id(m, NAME, path);
        var parsed =
                new MetricExpressionParser().parse(expression(m, path), path + ROOT + EXPRESSION);
        return new OssieModel.Metric(
                name,
                parsed.dataset(),
                parsed.field(),
                parsed.aggregation(),
                string(m, DESCRIPTION, path, false),
                display(ext, name, path),
                string(ext, UNIT, path, false),
                synonyms(m, path),
                parsed.predicates());
    }

    private OssieModel.Relationship relationship(JsonNode r, String path) throws Exception {
        keys(r, path, NAME, FROM, TO, FROM_COLUMNS, TO_COLUMNS, EXT);
        var ext = extension(r, path, JOIN);
        var from = strings(r, FROM_COLUMNS, path);
        var to = strings(r, TO_COLUMNS, path);
        require(from.size() == 1 && to.size() == 1, path, "只支持单字段关联");
        String join = string(ext, JOIN, path, true);
        require(Set.of(LEFT, INNER).contains(join), path, "不支持的关联类型");
        return new OssieModel.Relationship(
                id(r, NAME, path),
                id(r, FROM, path),
                id(r, TO, path),
                from.getFirst(),
                to.getFirst(),
                join);
    }

    private JsonNode extension(JsonNode node, String path, String... allowed) throws Exception {
        var extensions = array(node, EXT, path, false);
        require(extensions.size() <= 1, path, "只允许一项项目扩展");
        if (extensions.isEmpty()) return json.createObjectNode();
        var ext = extensions.getFirst();
        keys(ext, path, VENDOR, DATA);
        require(COMMON.equals(string(ext, VENDOR, path, true)), path, "不支持的扩展厂商");
        JsonNode decoded;
        try (var parser = json.createParser(string(ext, DATA, path, true))) {
            decoded = json.readTree(parser);
            require(parser.nextToken() == null, path, "扩展必须是单个 JSON 对象");
        }
        keys(decoded, path, NAMESPACE);
        require(decoded.has(NAMESPACE), path, "缺少项目命名空间");
        var own = decoded.get(NAMESPACE);
        keys(own, path, allowed);
        return own;
    }

    private static void references(OssieModel model) {
        Map<String, OssieModel.Dataset> datasets = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (var d : model.datasets()) {
            require(names.add(d.name()), ROOT, "资源名称重复");
            datasets.put(d.name(), d);
            Set<String> fields = new HashSet<>(), columns = new HashSet<>();
            for (var f : d.fields()) {
                require(fields.add(f.name()) && columns.add(f.column()), ROOT, "字段名称或物理列重复");
            }
            require(columns.containsAll(d.primaryKey()), ROOT, "主键必须是已声明的物理列");
            if (d.defaultTimeField() != null)
                require(
                        d.fields().stream()
                                .anyMatch(
                                        f ->
                                                f.name().equals(d.defaultTimeField())
                                                        && f.role() == OssieModel.Role.TIME),
                        ROOT,
                        "默认时间字段不是 TIME 字段");
        }
        for (var m : model.metrics()) {
            require(names.add(m.name()), ROOT, "资源名称重复");
            var d = datasets.get(m.dataset());
            require(d != null, ROOT, "指标引用未知数据集");
            Set<String> fields = new HashSet<>();
            d.fields().forEach(f -> fields.add(f.name()));
            require(
                    fields.contains(m.field())
                            && m.predicates().stream().allMatch(p -> fields.contains(p.field())),
                    ROOT,
                    "指标引用未知字段");
        }
        for (var r : model.relationships()) {
            require(names.add(r.name()), ROOT, "资源名称重复");
            var from = datasets.get(r.from());
            var to = datasets.get(r.to());
            require(from != null && to != null && !r.from().equals(r.to()), ROOT, "关联引用未知数据集或自身");
            require(
                    from.fields().stream().anyMatch(f -> f.column().equals(r.fromColumn()))
                            && to.primaryKey().equals(List.of(r.toColumn())),
                    ROOT,
                    "关联列或目标唯一键未声明");
        }
    }

    private static String expression(JsonNode node, String path) {
        var expr = node.get(EXPRESSION);
        keys(expr, path, DIALECTS);
        var dialects = array(expr, DIALECTS, path, true);
        require(dialects.size() == 1, path, "只允许一个方言");
        var item = dialects.getFirst();
        keys(item, path, DIALECT, EXPRESSION);
        require(ANSI.equals(string(item, DIALECT, path, true)), path, "只支持 ANSI_SQL");
        return string(item, EXPRESSION, path, true);
    }

    private static List<String> synonyms(JsonNode n, String path) {
        if (!n.has(AI)) return List.of();
        keys(n.get(AI), path, SYNONYMS);
        return strings(n.get(AI), SYNONYMS, path);
    }

    private static String display(JsonNode n, String fallback, String path) {
        String v = string(n, DISPLAY, path, false);
        return v == null ? fallback : v;
    }

    private static String id(JsonNode n, String key, String path) {
        String v = string(n, key, path, true);
        require(v.matches(IDENTIFIER) && v.length() <= 120, path, "标识须为小写字母开头的字母数字下划线，长度不超过120");
        return v;
    }

    private static String string(JsonNode n, String key, String path, boolean required) {
        if (!n.has(key)) {
            require(!required, path, "缺少必填属性: " + key);
            return null;
        }
        var v = n.get(key);
        require(v.isTextual() && !v.textValue().isBlank(), path, "属性须为非空字符串: " + key);
        return v.textValue();
    }

    private static List<String> strings(JsonNode n, String key, String path) {
        List<String> out = new ArrayList<>();
        for (var v : array(n, key, path, false)) {
            require(v.isTextual() && !v.textValue().isBlank(), path, "列表只能包含非空字符串");
            out.add(v.textValue());
        }
        return List.copyOf(out);
    }

    private static List<JsonNode> array(JsonNode n, String key, String path, boolean required) {
        if (!n.has(key)) {
            require(!required, path, "缺少必填集合: " + key);
            return List.of();
        }
        var a = n.get(key);
        require(a.isArray(), path, "属性须为数组: " + key);
        List<JsonNode> out = new ArrayList<>();
        a.forEach(out::add);
        return out;
    }

    private static void keys(JsonNode n, String path, String... allowed) {
        require(n != null && n.isObject(), path, "属性须为对象");
        Set<String> keys = Set.of(allowed);
        n.fieldNames()
                .forEachRemaining(
                        key -> require(keys.contains(key), path + ROOT + key, "不支持的协议属性"));
    }

    private static void require(boolean valid, String path, String message) {
        if (!valid) throw error(path, message);
    }

    private static ProtocolException error(String path, String message) {
        return new ProtocolException(path, message);
    }
}
