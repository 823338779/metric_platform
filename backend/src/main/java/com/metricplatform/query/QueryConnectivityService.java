package com.metricplatform.query;

import com.metricplatform.persistence.entity.DatasetEntity;
import com.metricplatform.persistence.service.DatasetEntityPersistenceService;
import com.metricplatform.query.SemanticCatalogReader.RelationshipDefinition;
import com.metricplatform.query.planner.DatasetRelationshipGraph;
import com.metricplatform.query.planner.DatasetRelationshipGraph.AmbiguousPaths;
import com.metricplatform.query.planner.DatasetRelationshipGraph.RelationshipEdge;
import com.metricplatform.query.planner.DatasetRelationshipGraph.UniquePath;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueryConnectivityService {
    private final DatasetEntityPersistenceService datasets;
    private final SemanticCatalogReader catalog;
    private final SemanticCatalogService semanticCatalog;

    public QueryDtos.QueryConnectivityResponse connectivity() {
        List<DatasetEntity> activeDatasets = datasets.list();
        Set<UUID> datasetIds = activeDatasets.stream()
                .map(DatasetEntity::getId)
                .collect(Collectors.toUnmodifiableSet());
        List<RelationshipDefinition> relationships = catalog.relationships().stream()
                .filter(value -> datasetIds.contains(value.sourceDatasetId())
                        && datasetIds.contains(value.targetDatasetId()))
                .toList();
        DatasetRelationshipGraph graph = new DatasetRelationshipGraph(
                relationships.stream()
                        .map(value -> new RelationshipEdge(
                                value.id(),
                                value.sourceDatasetId(),
                                value.targetDatasetId(),
                                value.cardinality()))
                        .toList());

        List<QueryDtos.ConnectivityPath> paths = new ArrayList<>();
        for (DatasetEntity source : activeDatasets) {
            for (DatasetEntity target : activeDatasets) {
                if (source.getId().equals(target.getId())
                        || !source.getDataSourceId().equals(target.getDataSourceId())) {
                    continue;
                }
                DatasetRelationshipGraph.PathResolution resolution =
                        graph.resolve(source.getId(), target.getId(), Set.of());
                if (resolution instanceof UniquePath unique) {
                    paths.add(new QueryDtos.ConnectivityPath(
                            source.getId(),
                            target.getId(),
                            QueryDtos.ConnectivityPathStatus.UNIQUE_PATH,
                            unique.edges().stream().map(RelationshipEdge::id).toList()));
                } else if (resolution instanceof AmbiguousPaths) {
                    paths.add(new QueryDtos.ConnectivityPath(
                            source.getId(),
                            target.getId(),
                            QueryDtos.ConnectivityPathStatus.AMBIGUOUS_PATH,
                            List.of()));
                }
            }
        }

        return new QueryDtos.QueryConnectivityResponse(
                semanticCatalog.catalogVersion(),
                activeDatasets.stream()
                        .map(value -> new QueryDtos.DatasetConnectivity(
                                value.getId(), value.getDataSourceId()))
                        .toList(),
                List.copyOf(paths));
    }
}
