package com.metricplatform.catalog;

import static com.metricplatform.ontology.OntologyContracts.BindingStatus.*;

import com.metricplatform.ontology.OntologyContracts.BindingStatus;
import com.metricplatform.ontology.OntologyContracts.Finding;
import java.util.Locale;
import java.util.UUID;

enum BindingEvidence {
    MATCHED(COMPATIBLE),
    NO_DEPLOYMENT(UNVERIFIED),
    UPSTREAM_UNAVAILABLE(UNVERIFIED),
    SERVICE_AUTHENTICATION_FAILED(UNVERIFIED),
    DEPLOYMENT_CHANGED(UNVERIFIED),
    AMBIGUOUS_DEPLOYMENT(NEEDS_REPAIR),
    ASSET_NOT_DEPLOYED(NEEDS_REPAIR),
    AMBIGUOUS_ASSET(NEEDS_REPAIR),
    COLUMN_UNDECLARED(UNVERIFIED),
    TYPE_UNKNOWN(UNVERIFIED),
    TARGET_NOT_ALLOWED(UNVERIFIED),
    TARGET_UNRESOLVED(UNVERIFIED),
    METADATA_CREDENTIALS_MISSING(UNVERIFIED),
    METADATA_INVISIBLE(UNVERIFIED),
    METADATA_PERMISSION_DENIED(UNVERIFIED),
    METADATA_TIMEOUT(UNVERIFIED),
    METADATA_UNAVAILABLE(UNVERIFIED),
    COLUMN_MISSING(NEEDS_REPAIR),
    TYPE_CONFLICT(NEEDS_REPAIR);

    boolean temporary() {
        return switch (this) {
            case METADATA_CREDENTIALS_MISSING,
                    METADATA_INVISIBLE,
                    METADATA_PERMISSION_DENIED,
                    METADATA_TIMEOUT,
                    METADATA_UNAVAILABLE ->
                    true;
            default -> false;
        };
    }

    final BindingStatus status;

    BindingEvidence(BindingStatus status) {
        this.status = status;
    }

    Finding finding(String kind, UUID id) {
        return new Finding(kind, id, status, name());
    }

    static BindingEvidence compareTypes(String left, String right) {
        if (left == null || right == null) return TYPE_UNKNOWN;
        if (left.equalsIgnoreCase(right)) return MATCHED;
        String first = family(left);
        String second = family(right);
        if (first.isEmpty() || second.isEmpty()) return TYPE_UNKNOWN;
        return first.equals(second) ? MATCHED : TYPE_CONFLICT;
    }

    // 只将明确属于不同类型族的声明判为冲突；无法识别的引擎类型保留未核实状态。
    private static String family(String type) {
        String base = type.toLowerCase(Locale.ROOT).replaceFirst("\\s*\\([^()]*\\)$", "").strip();
        return switch (base) {
            case "int",
                    "integer",
                    "tinyint",
                    "smallint",
                    "bigint",
                    "int2",
                    "int4",
                    "int8",
                    "real",
                    "float",
                    "float4",
                    "float8",
                    "double",
                    "double precision",
                    "decimal",
                    "numeric" ->
                    "numeric";
            case "varchar", "character varying", "char", "character", "text", "string" -> "text";
            case "bool", "boolean" -> "boolean";
            case "date",
                    "datetime",
                    "timestamp",
                    "timestamp without time zone",
                    "timestamp with time zone",
                    "timestamptz" ->
                    "temporal";
            case "uuid" -> "uuid";
            default -> "";
        };
    }

    static BindingEvidence combine(BindingEvidence left, BindingEvidence right) {
        if (left.status == NEEDS_REPAIR) return left;
        if (right.status == NEEDS_REPAIR || left == MATCHED) return right;
        return left;
    }
}
