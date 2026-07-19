package com.metricplatform.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DimensionMemberQueryRequestTest {
    @Test
    void omittedTextCreatesADeterministicBrowseRequest() {
        var request = new QueryDtos.DimensionMemberQueryRequest(null, null, null, null);

        assertThat(request.text()).isNull();
        assertThat(request.mode()).isEqualTo(QueryDtos.MemberMatchMode.AUTO);
        assertThat(request.limit()).isEqualTo(20);
    }

    @Test
    void rejectsSearchOnlyModesWithoutTextAndInvalidLimits() {
        assertThatThrownBy(
                        () ->
                                new QueryDtos.DimensionMemberQueryRequest(
                                        null, QueryDtos.MemberMatchMode.EXACT, 20, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new QueryDtos.DimensionMemberQueryRequest(
                                        "x", QueryDtos.MemberMatchMode.AUTO, 101, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
