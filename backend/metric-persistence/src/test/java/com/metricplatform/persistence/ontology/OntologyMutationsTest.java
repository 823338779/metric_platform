package com.metricplatform.persistence.ontology;

import static org.junit.jupiter.api.Assertions.*;
import static com.metricplatform.ontology.OntologyException.Code.*;

import com.metricplatform.ontology.OntologyContracts.*;
import com.metricplatform.ontology.OntologyException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OntologyMutationsTest extends OntologyGraphFixture {
    @Test
    void recordsBuildEvidenceOnlyForVerifiedMappingsAndPreservesItOnRename() {
        UUID firstBuild = UUID.randomUUID(), secondBuild = UUID.randomUUID();
        var order = object("订单");
        var key = order.properties().getFirst();
        var buyer = new Property(UUID.randomUUID(), "客户", "customer_id", "INTEGER");
        var initial = new ObjectType(order.id(), order.name(), "", order.datasetAssetId(), key.id(), List.of(key, buyer));
        store.createObject(scope, 0, initial, firstBuild, Set.of(key.id(), buyer.id()));
        var customer = object("客户");
        store.createObject(scope, 1, customer, firstBuild, Set.of(customer.primaryPropertyId()));
        var edge = relation("下单客户", initial, customer);
        store.createRelation(scope, 2, edge, firstBuild);
        var changed = new Property(buyer.id(), buyer.name(), "buyer_id", "INTEGER");
        var rebound = new ObjectType(order.id(), "订单信息", "", order.datasetAssetId(), key.id(), List.of(key, changed));
        store.updateObject(scope, 3, rebound, secondBuild, Set.of(buyer.id()));
        var redirected = new Relation(edge.id(), edge.name(), "", order.id(), buyer.id(), edge.targetObjectId(),
                edge.targetPropertyId(), edge.cardinality());
        store.updateRelation(scope, 4, redirected, secondBuild);
        var renamed = new Relation(edge.id(), "买家", "", order.id(), buyer.id(), edge.targetObjectId(),
                edge.targetPropertyId(), edge.cardinality());
        store.updateRelation(scope, 5, renamed);
        var renamedObject = new ObjectType(order.id(), "销售订单", "", order.datasetAssetId(), key.id(), List.of(key, changed));
        store.updateObject(scope, 6, renamedObject);
        try (var session = driver.session()) {
            Map<String, Object> params = Map.of("tenant", scope.tenantId(), "env", scope.environmentId().toString());
            var markers = session.run("""
                    MATCH (p:OntologyProperty {tenantId:$tenant, environmentId:$env})
                    RETURN p.id AS id, p.checkedBuildId AS build, p.checkedRevision AS revision
                    UNION ALL
                    MATCH ()-[r:ONTOLOGY_RELATION {tenantId:$tenant, environmentId:$env}]->()
                    RETURN r.id AS id, r.checkedBuildId AS build, r.checkedRevision AS revision
                    """, params).list(row -> List.of(row.get(0).asString(), row.get(1).asString(), row.get(2).asLong()));
            assertEquals(Set.of(
                    List.of(key.id().toString(), firstBuild.toString(), 1L),
                    List.of(buyer.id().toString(), secondBuild.toString(), 4L),
                    List.of(customer.primaryPropertyId().toString(), firstBuild.toString(), 2L),
                    List.of(edge.id().toString(), secondBuild.toString(), 5L)), Set.copyOf(markers));
        }
        var newPrimary = new ObjectType(order.id(), renamedObject.name(), "", order.datasetAssetId(), buyer.id(), List.of(key, changed));
        assertEquals(8, store.updateObject(scope, 7, newPrimary, secondBuild, Set.of(buyer.id())));
        assertEquals(List.of(renamed), store.read(scope).relations());
    }

    @Test
    void removingReferencedPrimaryKeyReturnsBlockingRelationBeforeKeyValidation() {
        var order = object("订单");
        var customer = object("客户");
        store.createObject(scope, 0, order);
        store.createObject(scope, 1, customer);
        var buyer = relation("下单客户", order, customer);
        store.createRelation(scope, 2, buyer);
        for (UUID primary : Arrays.asList(order.primaryPropertyId(), null)) {
            var missingKey = new ObjectType(order.id(), order.name(), "", order.datasetAssetId(), primary, List.of());
            var conflict = assertThrows(OntologyException.class, () -> store.updateObject(scope, 3, missingKey));
            assertEquals(CONFLICT, conflict.code());
            assertEquals(List.of(new Reference(buyer.id(), buyer.name())), conflict.references());
        }
        assertEquals(3, store.read(scope).revision());
    }

    @Test
    void replacesPrimaryKeyAtomicallyAndKeepsOtherScopesIndependent() {
        var original = object("订单");
        var destination = new Property(UUID.randomUUID(), "收货方", "recipient_id", null);
        var properties = List.of(original.properties().getFirst(), destination);
        var initial = new ObjectType(original.id(), original.name(), null, original.datasetAssetId(),
                original.primaryPropertyId(), properties);
        Scope other = new Scope("other", scope.environmentId());
        store.createObject(other, 0, initial);
        store.createObject(scope, 0, initial);
        var dangling = new ObjectType(initial.id(), initial.name(), null, initial.datasetAssetId(),
                initial.primaryPropertyId(), List.of(destination));
        assertEquals(INVALID, assertThrows(OntologyException.class, () -> store.updateObject(scope, 1, dangling)).code());
        var replaced = new ObjectType(initial.id(), "订单（已改名）", null, initial.datasetAssetId(),
                destination.id(), List.of(destination));
        assertEquals(2, store.updateObject(scope, 1, replaced));
        assertEquals(List.of(replaced), store.read(scope).objects());
        store.deleteObject(other, 1, initial.id());
        assertTrue(store.read(other).objects().isEmpty());
        assertEquals(List.of(replaced), store.read(scope).objects());
    }

    static Relation relation(String name, ObjectType source, ObjectType target) {
        return new Relation(UUID.randomUUID(), name, "", source.id(), source.primaryPropertyId(),
                target.id(), target.primaryPropertyId(), Cardinality.MANY_TO_ONE);
    }

    @Test
    void namedParallelEdgesRetainIdentityAndProtectReferencedBindings() {
        var orders = object("订单");
        var customers = object("客户");
        store.createObject(scope, 0, orders);
        store.createObject(scope, 1, customers);
        var buyer = relation("下单客户", orders, customers);
        var recipient = relation("收货人", orders, customers);
        store.createRelation(scope, 2, buyer);
        store.createRelation(scope, 3, recipient);
        var renamed = new Relation(buyer.id(), "购买者", "", buyer.sourceObjectId(), buyer.sourcePropertyId(),
                buyer.targetObjectId(), buyer.targetPropertyId(), buyer.cardinality());
        store.updateRelation(scope, 4, renamed);
        assertEquals(Set.of(renamed, recipient), Set.copyOf(store.read(scope).relations()));
        var blocked = assertThrows(OntologyException.class, () -> store.deleteObject(scope, 5, orders.id()));
        assertEquals(CONFLICT, blocked.code());
        assertEquals(Set.of(new Reference(buyer.id(), "购买者"), new Reference(recipient.id(), "收货人")),
                Set.copyOf(blocked.references()));

        var rebind = new ObjectType(orders.id(), orders.name(), "", UUID.randomUUID(),
                orders.primaryPropertyId(), orders.properties());
        var changedKey = new Property(orders.primaryPropertyId(), "编号", "new_id", "INTEGER");
        var replaced = new ObjectType(orders.id(), orders.name(), "", orders.datasetAssetId(), changedKey.id(), List.of(changedKey));
        assertEquals(CONFLICT, assertThrows(OntologyException.class, () -> store.updateObject(scope, 5, rebind)).code());
        assertEquals(CONFLICT, assertThrows(OntologyException.class, () -> store.updateObject(scope, 5, replaced)).code());
        store.deleteRelation(scope, 5, recipient.id());
        assertEquals(List.of(renamed), store.read(scope).relations());
        assertEquals(CONFLICT, assertThrows(OntologyException.class, () -> store.deleteRelation(scope, 5, buyer.id())).code());
        store.deleteRelation(scope, 6, buyer.id());
        store.updateObject(scope, 7, rebind);
        store.deleteObject(scope, 8, orders.id());
        assertEquals(List.of(customers), store.read(scope).objects());
    }

    @Test
    void rejectsForeignObjectsAndPropertiesWithoutLeakingReferences() {
        var a = object("订单");
        var b = object("客户");
        store.createObject(scope, 0, a);
        store.createObject(scope, 1, b);
        for (Scope foreign : List.of(new Scope("other", scope.environmentId()), new Scope(scope.tenantId(), UUID.randomUUID()))) {
            var foreignObject = object("客户");
            store.createObject(foreign, 0, foreignObject);
            var refused = assertThrows(OntologyException.class,
                    () -> store.createRelation(scope, 2, relation("非法", a, foreignObject)));
            assertEquals(NOT_FOUND, refused.code());
            assertTrue(refused.references().isEmpty());
        }
        var swapped = new Relation(UUID.randomUUID(), "非法", "", a.id(), b.primaryPropertyId(),
                b.id(), a.primaryPropertyId(), Cardinality.ONE_TO_ONE);
        assertEquals(INVALID, assertThrows(OntologyException.class, () -> store.createRelation(scope, 2, swapped)).code());
        var reused = new ObjectType(a.id(), a.name(), "", a.datasetAssetId(), b.primaryPropertyId(), b.properties());
        assertEquals(INVALID, assertThrows(OntologyException.class, () -> store.updateObject(scope, 2, reused)).code());
        assertEquals(2, store.read(scope).revision());
        assertTrue(store.read(scope).relations().isEmpty());
    }
}
