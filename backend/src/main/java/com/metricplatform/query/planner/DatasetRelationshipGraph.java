package com.metricplatform.query.planner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class DatasetRelationshipGraph {
    public enum Cardinality {
        ONE_TO_ONE,
        MANY_TO_ONE
    }

    public record RelationshipEdge(
            UUID id, UUID sourceDatasetId, UUID targetDatasetId, Cardinality cardinality) {}

    public sealed interface PathResolution permits UniquePath, MissingPath, AmbiguousPaths {}

    public record UniquePath(List<RelationshipEdge> edges) implements PathResolution {}

    public record MissingPath() implements PathResolution {}

    public record AmbiguousPaths(List<List<RelationshipEdge>> paths) implements PathResolution {}

    private final List<RelationshipEdge> edges;

    public DatasetRelationshipGraph(List<RelationshipEdge> edges) {
        this.edges = List.copyOf(edges);
    }

    public PathResolution resolve(
            UUID sourceDatasetId, UUID targetDatasetId, Set<UUID> allowedRelationshipIds) {
        if (sourceDatasetId.equals(targetDatasetId)) {
            return new UniquePath(List.of());
        }
        List<List<RelationshipEdge>> paths = new ArrayList<>();
        search(
                sourceDatasetId,
                targetDatasetId,
                allowedRelationshipIds,
                new HashSet<>(),
                new ArrayList<>(),
                paths);
        if (paths.isEmpty()) {
            return new MissingPath();
        }
        if (paths.size() == 1) {
            return new UniquePath(List.copyOf(paths.getFirst()));
        }
        return new AmbiguousPaths(paths.stream().map(List::copyOf).toList());
    }

    private void search(
            UUID current,
            UUID target,
            Set<UUID> allowedIds,
            Set<UUID> visitedEdges,
            List<RelationshipEdge> path,
            List<List<RelationshipEdge>> paths) {
        if (paths.size() > 1) {
            return;
        }
        for (RelationshipEdge edge : edges) {
            if (!edge.sourceDatasetId().equals(current)
                    || (!allowedIds.isEmpty() && !allowedIds.contains(edge.id()))
                    || !visitedEdges.add(edge.id())) {
                continue;
            }
            path.add(edge);
            if (edge.targetDatasetId().equals(target)) {
                paths.add(new ArrayList<>(path));
            } else {
                search(edge.targetDatasetId(), target, allowedIds, visitedEdges, path, paths);
            }
            path.removeLast();
            visitedEdges.remove(edge.id());
        }
    }
}
