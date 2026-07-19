package com.metricplatform.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

class SemanticObjectCursorCodecTest {
    private final SemanticObjectCursorCodec codec =
            new SemanticObjectCursorCodec(new ObjectMapper());

    @Test
    void roundTripsAValidCursorAndRejectsAnotherCatalogOrSort() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000011");
        String cursor =
                codec.encode(
                        new SemanticObjectCursorCodec.Cursor(
                                "catalog-1",
                                QueryDtos.SemanticObjectSort.NAME_ASC,
                                "metric-name",
                                id));

        assertThat(codec.decode(cursor, "catalog-1", QueryDtos.SemanticObjectSort.NAME_ASC))
                .isEqualTo(
                        new SemanticObjectCursorCodec.Cursor(
                                "catalog-1",
                                QueryDtos.SemanticObjectSort.NAME_ASC,
                                "metric-name",
                                id));
        assertThatThrownBy(
                        () ->
                                codec.decode(
                                        cursor, "catalog-2", QueryDtos.SemanticObjectSort.NAME_ASC))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                codec.decode(
                                        cursor,
                                        "catalog-1",
                                        QueryDtos.SemanticObjectSort.UPDATED_AT_DESC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedAndUnknownCursorFields() {
        String forged =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "{\"catalogVersion\":\"catalog-1\",\"sort\":\"NAME_ASC\",\"lastKey\":\"x\",\"lastId\":\"00000000-0000-0000-0000-000000000011\",\"extra\":true}"
                                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(
                        () ->
                                codec.decode(
                                        forged, "catalog-1", QueryDtos.SemanticObjectSort.NAME_ASC))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                codec.decode(
                                        "not-base64!",
                                        "catalog-1",
                                        QueryDtos.SemanticObjectSort.NAME_ASC))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
