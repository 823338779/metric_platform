package com.metricplatform.ontology;

import com.metricplatform.ontology.OntologyContracts.*;
import java.util.*;

public final class OntologyReferences {
    private OntologyReferences() {}

    public static Set<UUID> checkUpdate(Topology graph, ObjectType original, ObjectType replacement) {
        if (original == null) return Set.of();
        boolean datasetChanged = !original.datasetAssetId().equals(replacement.datasetAssetId());
        var nextBindings = new HashMap<UUID, Property>();
        for (Property p : replacement.properties()) nextBindings.put(p.id(), p);
        var changed = new HashSet<UUID>();
        for (Property p : original.properties()) {
            var next = nextBindings.get(p.id());
            if (datasetChanged || next == null || !p.columnName().equals(next.columnName())
                    || !Objects.equals(p.declaredType(), next.declaredType())) changed.add(p.id());
        }
        check(graph, original.id(), datasetChanged, changed);
        return changed;
    }

    public static void checkDelete(Topology graph, UUID objectId) {
        check(graph, objectId, true, Set.of());
    }

    private static void check(Topology graph, UUID objectId, boolean all, Set<UUID> changed) {
        var references = graph.relations().stream()
                .filter(r -> (r.sourceObjectId().equals(objectId) && (all || changed.contains(r.sourcePropertyId())))
                        || (r.targetObjectId().equals(objectId) && (all || changed.contains(r.targetPropertyId()))))
                .map(r -> new Reference(r.id(), r.name())).toList();
        if (!references.isEmpty()) {
            throw new OntologyException(OntologyException.Code.CONFLICT, "请先处理引用此绑定的关系", references);
        }
    }
}
