package com.metricplatform.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;

class GitWriteConflictTest {
    @Test
    void databaseOwnershipGuardUsesTheExistingConflictContract() {
        var result =
                new ApiExceptionHandler()
                        .persistence(
                                new UncategorizedSQLException(
                                        "write",
                                        "SQL",
                                        new SQLException("private database detail", "GIT01")));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(result.getBody().getDetail()).contains("Git-managed").doesNotContain("private");
    }

    @Test
    void unrelatedSqlFailuresKeepTheirExistingUpstreamErrorContract() {
        var result =
                new ApiExceptionHandler()
                        .persistence(
                                new UncategorizedSQLException(
                                        "write",
                                        "SQL",
                                        new SQLException("private database detail", "XX000")));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(result.getBody().getDetail()).doesNotContain("private");
    }
}
