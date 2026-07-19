package com.metricplatform.query;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Binds a member cursor to one dimension, member snapshot, and query shape. */
@Component
final class DimensionMemberCursorCodec {
    private final ObjectMapper objectMapper;

    DimensionMemberCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper =
                objectMapper
                        .copy()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    String encode(Cursor cursor) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(cursor));
        } catch (IOException failure) {
            throw new IllegalStateException("dimension member cursor cannot be encoded", failure);
        }
    }

    Cursor decode(
            String encoded,
            UUID dimensionId,
            String memberVersion,
            String text,
            QueryDtos.MemberMatchMode mode) {
        try {
            Cursor cursor =
                    objectMapper.readValue(Base64.getUrlDecoder().decode(encoded), Cursor.class);
            if (!cursor.dimensionId().equals(dimensionId)
                    || !cursor.memberVersion().equals(memberVersion)
                    || !Objects.equals(cursor.text(), text)
                    || cursor.mode() != mode) {
                throw new IllegalArgumentException(
                        "dimension member cursor is stale or incompatible");
            }
            return cursor;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException | IOException failure) {
            throw new IllegalArgumentException("dimension member cursor is invalid", failure);
        }
    }

    record Cursor(
            UUID dimensionId,
            String memberVersion,
            String text,
            QueryDtos.MemberMatchMode mode,
            String lastValue) {
        Cursor {
            Objects.requireNonNull(dimensionId, "dimensionId must not be null");
            if (memberVersion == null || memberVersion.isBlank()) {
                throw new IllegalArgumentException("memberVersion must not be blank");
            }
            Objects.requireNonNull(mode, "mode must not be null");
            if (lastValue == null || lastValue.isBlank()) {
                throw new IllegalArgumentException("lastValue must not be blank");
            }
        }
    }
}
