package com.metricplatform.ontology;

import com.metricplatform.ontology.OntologyContracts.*;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface OntologyStore {
    Topology read(Scope scope);
    default long createObject(Scope scope, long expectedRevision, ObjectType object) {
        return createObject(scope, expectedRevision, object, null, Set.of());
    }
    default long updateObject(Scope scope, long expectedRevision, ObjectType object) {
        return updateObject(scope, expectedRevision, object, null, Set.of());
    }
    long createObject(Scope scope, long expectedRevision, ObjectType object, UUID sourceBuildId, Set<UUID> verifiedPropertyIds);
    long updateObject(Scope scope, long expectedRevision, ObjectType object, UUID sourceBuildId, Set<UUID> verifiedPropertyIds);
    long deleteObject(Scope scope, long expectedRevision, UUID objectId);
    default long createRelation(Scope scope, long expectedRevision, Relation relation) {
        return createRelation(scope, expectedRevision, relation, null);
    }
    default long updateRelation(Scope scope, long expectedRevision, Relation relation) {
        return updateRelation(scope, expectedRevision, relation, null);
    }
    long createRelation(Scope scope, long expectedRevision, Relation relation, UUID sourceBuildId);
    long updateRelation(Scope scope, long expectedRevision, Relation relation, UUID sourceBuildId);
    long deleteRelation(Scope scope, long expectedRevision, UUID relationId);
    boolean saveReport(Scope scope, BindingReport report);
    Optional<BindingReport> readReport(Scope scope, UUID buildId, long revision);
}
