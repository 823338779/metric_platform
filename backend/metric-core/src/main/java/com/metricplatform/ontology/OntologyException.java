package com.metricplatform.ontology;

import com.metricplatform.ontology.OntologyContracts.Reference;
import java.util.List;

public final class OntologyException extends RuntimeException {
    public enum Code { NOT_FOUND, CONFLICT, INVALID, UNAVAILABLE }

    private final Code code;
    private final List<Reference> references;

    public OntologyException(Code code, String message) {
        this(code, message, List.of());
    }

    public OntologyException(Code code, String message, List<Reference> references) {
        super(message);
        this.code = code;
        this.references = List.copyOf(references);
    }

    public Code code() { return code; }

    public List<Reference> references() { return references; }
}
