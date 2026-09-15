package com.metricplatform.gitsync;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OssieParserTest {
    @Test
    void distinguishesQuotedPunctuationFromActualSqlComments() {
        var parser = new MetricExpressionParser();
        assertThat(
                        parser.parse(
                                        "SUM(CASE WHEN payments.state = 'a;--/*b' THEN payments.amount END)",
                                        "/metric")
                                .predicates()
                                .getFirst()
                                .values())
                .containsExactly("a;--/*b");
        for (var sql :
                new String[] {
                    "SUM(payments.amount) -- comment",
                    "SUM(/* comment */ payments.amount)",
                    "SUM(payments.amount);"
                }) {
            assertThatThrownBy(() -> parser.parse(sql, "/metric"))
                    .isInstanceOf(ProtocolException.class);
        }
    }

    @Test
    void decodesSqlStringEscapesBeforeBinding() {
        var parsed =
                new MetricExpressionParser()
                        .parse(
                                "SUM(CASE WHEN payments.state = 'O''REILLY' THEN payments.amount END)",
                                "/metric");
        assertThat(parsed.predicates().getFirst().values()).containsExactly("O'REILLY");
    }

    @Test
    void rejectsUnsupportedPredicateModifiers() {
        for (var condition :
                new String[] {
                    "payments.state NOTNULL",
                    "payments.state ISNULL",
                    "payments.state GLOBAL IN ('PAID')",
                    "PRIOR payments.amount = 1",
                    "payments.amount (+) = 1",
                    "payments.amount = 1 (+)"
                }) {
            assertThatThrownBy(
                            () ->
                                    new MetricExpressionParser()
                                            .parse(
                                                    "SUM(CASE WHEN "
                                                            + condition
                                                            + " THEN payments.amount END)",
                                                    "/metric"))
                    .as(condition)
                    .isInstanceOf(ProtocolException.class);
        }
    }

    @Test
    void rejectsOverflowedProfileVersion() throws Exception {
        var yaml =
                new String(example(), StandardCharsets.UTF_8)
                        .replace("\"profile_version\":1", "\"profile_version\":4294967297");
        assertThat(yaml).contains("4294967297");
        assertThatThrownBy(() -> new OssieParser().parse(yaml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ProtocolException.class);
    }

    private byte[] example() throws Exception {
        try (var input = getClass().getResourceAsStream("/model.yaml")) {
            return input.readAllBytes();
        }
    }

    @Test
    void normalizesConditionalMetricAndLogicalPhysicalReferences() throws Exception {
        var model = new OssieParser().parse(example());
        assertThat(model.name()).isEqualTo("payment_analytics");
        assertThat(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .valueToTree(model)
                                .path("description")
                                .asText())
                .isEqualTo("支付交易分析");
        assertThat(model.datasets()).hasSize(2);
        assertThat(model.metrics().getFirst().aggregation()).isEqualTo("SUM");
        assertThat(model.metrics().getFirst().predicates().getFirst().values())
                .containsExactly("PAID");
        assertThat(model.relationships().getFirst().toColumn()).isEqualTo("region_code");
    }

    @Test
    void rejectsUnknownFieldsDuplicateKeysAndAnchors() throws Exception {
        var yaml = new String(example(), StandardCharsets.UTF_8);
        for (var invalid :
                new String[] {
                    "dimensions: []\n" + yaml,
                    "version: '0.1.1'\n" + yaml,
                    yaml.replace("source: fact_payment", "source: &source fact_payment"),
                    yaml + "\n---\nversion: '0.1.1'\n"
                }) {
            assertThatThrownBy(
                            () -> new OssieParser().parse(invalid.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ProtocolException.class);
        }
    }

    @Test
    void rejectsUnsupportedCalculationsAndUnknownReferences() throws Exception {
        var yaml = new String(example(), StandardCharsets.UTF_8);
        var expression = "SUM(CASE WHEN payments.state = 'PAID' THEN payments.amount END)";
        for (var invalid :
                new String[] {
                    "SUM(payments.missing)",
                    "COUNT(*)",
                    "SUM(payments.amount) + 1",
                    "SUM(CASE WHEN payments.state = 'PAID' THEN payments.amount ELSE 0 END)",
                    "SUM(CASE WHEN payments.state = 'PAID' OR payments.state = 'NEW' THEN payments.amount END)",
                    "SUM(payments.amount); DELETE FROM payments",
                    "SUM(regions.code) OVER ()"
                }) {
            var changed = yaml.replace(expression, invalid).getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> new OssieParser().parse(changed))
                    .isInstanceOf(ProtocolException.class);
        }
    }
}
