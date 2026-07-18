package com.metricplatform.query.planner;

import static com.metricplatform.query.planner.DatasetRelationshipGraph.Cardinality.MANY_TO_ONE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DatasetRelationshipGraphTest {
    private static final UUID ORDER = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID CUSTOMER = UUID.fromString("60000000-0000-0000-0000-000000000002");
    private static final UUID REGION = UUID.fromString("60000000-0000-0000-0000-000000000003");
    private static final UUID ALTERNATE = UUID.fromString("60000000-0000-0000-0000-000000000004");
    private static final UUID ORDER_CUSTOMER =
            UUID.fromString("60000000-0000-0000-0000-000000000011");
    private static final UUID CUSTOMER_REGION =
            UUID.fromString("60000000-0000-0000-0000-000000000012");
    private static final UUID ORDER_ALTERNATE =
            UUID.fromString("60000000-0000-0000-0000-000000000013");
    private static final UUID ALTERNATE_REGION =
            UUID.fromString("60000000-0000-0000-0000-000000000014");

    @Test
    void resolvesAUniqueNonExpandingMultiHopPath() {
        DatasetRelationshipGraph graph = graph(false);

        DatasetRelationshipGraph.PathResolution resolution = graph.resolve(ORDER, REGION, Set.of());

        assertThat(resolution).isInstanceOf(DatasetRelationshipGraph.UniquePath.class);
        assertThat(((DatasetRelationshipGraph.UniquePath) resolution).edges())
                .extracting(DatasetRelationshipGraph.RelationshipEdge::id)
                .containsExactly(ORDER_CUSTOMER, CUSTOMER_REGION);
    }

    @Test
    void rejectsReverseManyToOneTraversal() {
        assertThat(graph(false).resolve(CUSTOMER, ORDER, Set.of()))
                .isInstanceOf(DatasetRelationshipGraph.MissingPath.class);
    }

    @Test
    void reportsDisconnectedDatasetsAndTerminatesCycles() {
        assertThat(graph(false).resolve(ORDER, ALTERNATE, Set.of()))
                .isInstanceOf(DatasetRelationshipGraph.MissingPath.class);

        DatasetRelationshipGraph cyclic =
                new DatasetRelationshipGraph(
                        List.of(
                                new DatasetRelationshipGraph.RelationshipEdge(
                                        ORDER_CUSTOMER, ORDER, CUSTOMER, MANY_TO_ONE),
                                new DatasetRelationshipGraph.RelationshipEdge(
                                        UUID.randomUUID(), CUSTOMER, ORDER, MANY_TO_ONE),
                                new DatasetRelationshipGraph.RelationshipEdge(
                                        CUSTOMER_REGION, CUSTOMER, REGION, MANY_TO_ONE)));
        DatasetRelationshipGraph.PathResolution resolution =
                cyclic.resolve(ORDER, REGION, Set.of());
        assertThat(resolution).isInstanceOf(DatasetRelationshipGraph.UniquePath.class);
        assertThat(((DatasetRelationshipGraph.UniquePath) resolution).edges())
                .extracting(DatasetRelationshipGraph.RelationshipEdge::id)
                .containsExactly(ORDER_CUSTOMER, CUSTOMER_REGION);
    }

    @Test
    void reportsAmbiguityAndAllowsAnExplicitSubgraph() {
        DatasetRelationshipGraph graph = graph(true);

        assertThat(graph.resolve(ORDER, REGION, Set.of()))
                .isInstanceOf(DatasetRelationshipGraph.AmbiguousPaths.class);
        DatasetRelationshipGraph.PathResolution selected =
                graph.resolve(ORDER, REGION, Set.of(ORDER_CUSTOMER, CUSTOMER_REGION));
        assertThat(selected).isInstanceOf(DatasetRelationshipGraph.UniquePath.class);
    }

    private static DatasetRelationshipGraph graph(boolean alternativePath) {
        List<DatasetRelationshipGraph.RelationshipEdge> edges =
                new java.util.ArrayList<>(
                        List.of(
                                new DatasetRelationshipGraph.RelationshipEdge(
                                        ORDER_CUSTOMER, ORDER, CUSTOMER, MANY_TO_ONE),
                                new DatasetRelationshipGraph.RelationshipEdge(
                                        CUSTOMER_REGION, CUSTOMER, REGION, MANY_TO_ONE)));
        if (alternativePath) {
            edges.add(
                    new DatasetRelationshipGraph.RelationshipEdge(
                            ORDER_ALTERNATE, ORDER, ALTERNATE, MANY_TO_ONE));
            edges.add(
                    new DatasetRelationshipGraph.RelationshipEdge(
                            ALTERNATE_REGION, ALTERNATE, REGION, MANY_TO_ONE));
        }
        return new DatasetRelationshipGraph(edges);
    }
}
