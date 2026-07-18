package com.metricplatform.query;

public final class IdentifierQuoter {
    private IdentifierQuoter() {}

    public static String quote(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Identifier is required");
        return "`" + value.replace("`", "``") + "`";
    }
}
