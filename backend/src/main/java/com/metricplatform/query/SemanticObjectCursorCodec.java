package com.metricplatform.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Encodes catalog-bound pagination state without exposing database offsets. */
@Component
public final class SemanticObjectCursorCodec {
    private final ObjectMapper objectMapper;

    public SemanticObjectCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper =
                objectMapper
                        .copy()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public String encode(Cursor cursor) {
        Objects.requireNonNull(cursor, "cursor must not be null");
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(cursor));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("semantic object cursor cannot be encoded", failure);
        }
    }

    public Cursor decode(
            String encoded,
            String expectedCatalogVersion,
            QueryDtos.SemanticObjectSort expectedSort) {
        try {
            byte[] json =
                    Base64.getUrlDecoder().decode(encoded.getBytes(StandardCharsets.US_ASCII));
            Cursor cursor = objectMapper.readValue(json, Cursor.class);
            if (!Objects.equals(cursor.catalogVersion(), expectedCatalogVersion)
                    || cursor.sort() != expectedSort) {
                throw new IllegalArgumentException(
                        "semantic object cursor is stale or incompatible");
            }
            return cursor;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException | IOException failure) {
            throw new IllegalArgumentException("semantic object cursor is invalid", failure);
        }
    }

    public record Cursor(
            String catalogVersion, QueryDtos.SemanticObjectSort sort, String lastKey, UUID lastId) {
        public Cursor {
            if (catalogVersion == null || catalogVersion.isBlank()) {
                throw new IllegalArgumentException("catalogVersion must not be blank");
            }
            Objects.requireNonNull(sort, "sort must not be null");
            if (lastKey == null || lastKey.isBlank()) {
                throw new IllegalArgumentException("lastKey must not be blank");
            }
            Objects.requireNonNull(lastId, "lastId must not be null");
        }
    }
}
