package com.metricplatform.ontology;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

public final class OntologyProblem extends ResponseStatusException {
    private final String code;

    public OntologyProblem(HttpStatus status, String code, String detail) {
        super(status, detail);
        this.code = code;
        getBody().setProperty("code", code);
        getBody().setProperty("retryable", status == HttpStatus.SERVICE_UNAVAILABLE);
        getBody().setProperty("references", List.of());
    }

    public String code() {
        return code;
    }
}
