package com.metricplatform.ontology;

import com.metricplatform.ontology.OntologyContracts.*;
import java.util.Optional;
import java.util.UUID;

public interface OntologyStore {
    Topology read(Scope scope);
    long createObject(Scope scope, long expectedRevision, ObjectType object);
    long updateObject(Scope scope, long expectedRevision, ObjectType object);
    long deleteObject(Scope scope, long expectedRevision, UUID objectId);
    long createRelation(Scope scope, long expectedRevision, Relation relation);
    long updateRelation(Scope scope, long expectedRevision, Relation relation);
    long deleteRelation(Scope scope, long expectedRevision, UUID relationId);
    boolean saveReport(Scope scope, BindingReport report);
    Optional<BindingReport> readReport(Scope scope, UUID buildId, long revision);
}
