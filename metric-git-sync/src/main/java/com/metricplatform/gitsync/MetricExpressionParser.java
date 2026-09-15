package com.metricplatform.gitsync;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;

/** 只接受可无损转换为基础聚合与 AND 谓词的 AST，绝不执行协议中的 SQL。 */
final class MetricExpressionParser {
    private static final Set<String> AGGREGATIONS = Set.of("SUM", "AVG", "MIN", "MAX", "COUNT");
    private static final String COUNT = "COUNT", DISTINCT = "COUNT_DISTINCT";
    private static final String ID = "[a-z][a-z0-9_]*";
    private static final String EQ = "EQ",
            NE = "NE",
            GT = "GT",
            GTE = "GTE",
            LT = "LT",
            LTE = "LTE",
            IN = "IN",
            NOT_IN = "NOT_IN",
            IS_NULL = "IS_NULL",
            IS_NOT_NULL = "IS_NOT_NULL";

    record Parsed(
            String dataset,
            String field,
            String aggregation,
            List<OssieModel.Predicate> predicates) {}

    Parsed parse(String sql, String path) {
        try {
            // 单表达式全量解析，禁止注释和分号，以免尾随语句或修饰符被归一化时遗漏。
            checkTokens(sql, path);
            Expression parsed =
                    CCJSqlParserUtil.parseExpression(sql, false, p -> p.withTimeOut(1000));
            if (!(parsed instanceof Function f)) throw invalid(path);
            String agg = f.getName().toUpperCase(Locale.ROOT);
            if (!AGGREGATIONS.contains(agg)
                    || f.getParameters() == null
                    || f.getParameters().size() != 1
                    || (f.isDistinct() && !COUNT.equals(agg))
                    || f.isAllColumns()
                    || f.isUnique()
                    || f.getNamedParameters() != null
                    || f.getAttribute() != null
                    || f.getKeep() != null
                    || f.getOrderByElements() != null
                    || f.getHavingClause() != null
                    || f.getLimit() != null
                    || f.getNullHandling() != null
                    || f.getExtraKeyword() != null
                    || f.isEscaped()
                    || f.getOnOverflowTruncate() != null) throw invalid(path);
            Expression arg = f.getParameters().getFirst();
            Expression condition = null;
            if (arg instanceof CaseExpression c) {
                if (c.getSwitchExpression() != null
                        || c.getElseExpression() != null
                        || c.getWhenClauses().size() != 1) throw invalid(path);
                var when = c.getWhenClauses().getFirst();
                arg = when.getThenExpression();
                condition = when.getWhenExpression();
            }
            Column column = column(arg, path);
            List<OssieModel.Predicate> predicates = new ArrayList<>();
            if (condition != null)
                predicates(condition, column.getTable().getName(), predicates, path);
            return new Parsed(
                    column.getTable().getName(),
                    column.getColumnName(),
                    f.isDistinct() ? DISTINCT : agg,
                    List.copyOf(predicates));
        } catch (ProtocolException error) {
            throw error;
        } catch (Exception error) {
            throw invalid(path);
        }
    }

    private void predicates(
            Expression expression, String dataset, List<OssieModel.Predicate> out, String path) {
        if (out.size() >= 64) throw invalid(path);
        if (expression instanceof ParenthesedExpressionList<?> p && p.size() == 1) {
            predicates(p.getFirst(), dataset, out, path);
            return;
        }
        if (expression instanceof AndExpression and) {
            predicates(and.getLeftExpression(), dataset, out, path);
            predicates(and.getRightExpression(), dataset, out, path);
            return;
        }
        Expression left;
        String operator;
        List<Object> values = new ArrayList<>();
        if (expression instanceof IsNullExpression n) {
            if (n.isUseIsNull() || n.isUseNotNull()) throw invalid(path);
            left = n.getLeftExpression();
            operator = n.isNot() ? IS_NOT_NULL : IS_NULL;
        } else if (expression instanceof InExpression in
                && in.getRightExpression() instanceof ExpressionList<?> items) {
            if (in.isGlobal()
                    || in.getOldOracleJoinSyntax() != 0
                    || in.getOraclePriorPosition() != 0) throw invalid(path);
            left = in.getLeftExpression();
            operator = in.isNot() ? NOT_IN : IN;
            if (items.isEmpty() || items.size() > 100) throw invalid(path);
            items.forEach(item -> values.add(literal(item, path)));
            if (values.stream()
                            .map(v -> v instanceof Number ? Number.class : v.getClass())
                            .distinct()
                            .count()
                    != 1) throw invalid(path);
        } else if (expression instanceof BinaryExpression b) {
            if (b instanceof OldOracleJoinBinaryExpression oracle
                    && (oracle.getOldOracleJoinSyntax() != 0
                            || oracle.getOraclePriorPosition() != 0)) throw invalid(path);
            left = b.getLeftExpression();
            operator =
                    switch (b) {
                        case EqualsTo ignored -> EQ;
                        case NotEqualsTo ignored -> NE;
                        case GreaterThan ignored -> GT;
                        case GreaterThanEquals ignored -> GTE;
                        case MinorThan ignored -> LT;
                        case MinorThanEquals ignored -> LTE;
                        default -> throw invalid(path);
                    };
            values.add(literal(b.getRightExpression(), path));
        } else throw invalid(path);
        Column field = column(left, path);
        if (!dataset.equals(field.getTable().getName())) throw invalid(path);
        out.add(new OssieModel.Predicate(field.getColumnName(), operator, List.copyOf(values)));
    }

    private static Column column(Expression e, String path) {
        if (!(e instanceof Column c)
                || c.getTable() == null
                || !c.getFullyQualifiedName().matches(ID + "\\." + ID)) throw invalid(path);
        return c;
    }

    private static Object literal(Expression e, String path) {
        // SQL 的双单引号是转义写法；参数绑定接收解码后的原始字符串。
        if (e instanceof StringValue s && s.getPrefix() == null) return s.getNotExcapedValue();
        if (e instanceof LongValue n) return n.getBigIntegerValue();
        if (e instanceof DoubleValue n) return new BigDecimal(n.toString());
        if (e instanceof BooleanValue b) return b.getValue();
        if (e instanceof SignedExpression s && (s.getSign() == '-' || s.getSign() == '+')) {
            Object value = literal(s.getExpression(), path);
            if (value instanceof Number n)
                return new BigDecimal(n.toString())
                        .multiply(BigDecimal.valueOf(s.getSign() == '-' ? -1 : 1));
        }
        throw invalid(path);
    }

    private static void checkTokens(String sql, String path) {
        if (sql.length() > 8192) throw invalid(path);
        boolean quoted = false;
        // 分号和注释标记只有在字符串外才是语法，不能误拒绝合法业务值中的标点。
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (current == '\'') {
                if (quoted && next == '\'') {
                    i++;
                    continue;
                }
                quoted = !quoted;
            } else if (!quoted
                    && (current == ';'
                            || current == '-' && next == '-'
                            || current == '/' && next == '*')) {
                throw invalid(path);
            }
        }
    }

    private static ProtocolException invalid(String path) {
        return new ProtocolException(path, "指标表达式超出 Ossie Profile v1 支持范围");
    }
}
