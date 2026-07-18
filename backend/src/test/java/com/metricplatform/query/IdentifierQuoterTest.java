package com.metricplatform.query;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IdentifierQuoterTest {
    @Test
    void quotesAndEscapesStarRocksIdentifier() {
        assertEquals("`daily``sales`", IdentifierQuoter.quote("daily`sales"));
    }
}
