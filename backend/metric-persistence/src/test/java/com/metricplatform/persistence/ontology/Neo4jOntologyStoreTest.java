package com.metricplatform.persistence.ontology;

import static org.junit.jupiter.api.Assertions.*;
import static com.metricplatform.ontology.OntologyException.Code.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.metricplatform.ontology.OntologyContracts.*;
import com.metricplatform.ontology.OntologyException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.ServiceUnavailableException;

class Neo4jOntologyStoreTest extends OntologyGraphFixture {
    @Test
    void overlappingReadsShareFailedInitializationAndNextReadCanRecover() throws Exception {
        var disconnected = mock(Session.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        when(disconnected.run(anyString())).thenAnswer(call -> {
            attempts.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            throw new ServiceUnavailableException("offline");
        });
        var connection = mock(Driver.class);
        when(connection.session()).thenReturn(disconnected);
        var recovering = new Neo4jOntologyStore(connection);
        Callable<OntologyException.Code> read = () ->
                assertThrows(OntologyException.class, () -> recovering.read(scope)).code();
        var first = new FutureTask<>(read);
        var second = new FutureTask<>(read);
        Thread.ofPlatform().start(first);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread waiter = Thread.ofPlatform().start(second);
            await().atMost(5, TimeUnit.SECONDS).until(() ->
                    (waiter.getState() == Thread.State.BLOCKED || waiter.getState() == Thread.State.WAITING)
                    && Arrays.stream(waiter.getStackTrace()).anyMatch(frame ->
                            frame.getClassName().equals(Neo4jOntologyStore.class.getName())));
        } finally {
            release.countDown();
        }
        assertEquals(UNAVAILABLE, first.get(5, TimeUnit.SECONDS));
        assertEquals(UNAVAILABLE, second.get(5, TimeUnit.SECONDS));
        assertEquals(1, attempts.get());
        when(connection.session()).thenAnswer(call -> driver.session());
        assertEquals(0, recovering.read(scope).revision());
    }

    @Test
    void isolatesDefinitionsAndRollsBackInvalidProperties() {
        assertEquals(0, store.read(scope).revision());
        var orders = object("订单");
        assertEquals(1, store.createObject(scope, 0, orders));
        assertEquals(List.of(orders), store.read(scope).objects());
        assertEquals(List.of(), store.read(new Scope("other", scope.environmentId())).objects());
        assertEquals(0, store.read(new Scope(scope.tenantId(), UUID.randomUUID())).revision());
        var repeated = new ObjectType(orders.id(), "changed", "", orders.datasetAssetId(),
                orders.primaryPropertyId(), List.of(orders.properties().getFirst(), orders.properties().getFirst()));
        assertEquals(INVALID, assertThrows(OntologyException.class, () -> store.updateObject(scope, 1, repeated)).code());
        assertEquals(new Topology(1, List.of(orders), List.of()), store.read(scope));
        assertEquals(CONFLICT, assertThrows(OntologyException.class, () -> store.createObject(scope, 1, orders)).code());
        assertEquals(NOT_FOUND, assertThrows(OntologyException.class, () -> store.updateObject(scope, 1, object("未知"))).code());
    }

    @Test
    void concurrentFirstWritesHaveOneWinner() throws Exception {
        var gate = new CyclicBarrier(2);
        Callable<Boolean> write = () -> {
            gate.await(10, TimeUnit.SECONDS);
            try {
                store.createObject(scope, 0, object("订单"));
                return true;
            } catch (OntologyException ex) {
                assertEquals(CONFLICT, ex.code());
                return false;
            }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(write);
            var b = pool.submit(write);
            assertNotEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, store.read(scope).objects().size());
        assertEquals(1, store.read(scope).revision());
    }

    @Test
    void lateObservationCannotOverwriteNewerEvidence() {
        var obj = object("订单");
        store.createObject(scope,0,obj);
        var a = store.beginObservation(scope);
        var b = store.beginObservation(scope);
        assertTrue(b > a);
        var build = UUID.randomUUID();
        var observation = new ColumnObservation("bigint(20)", "2026-11-24T12:00:00Z", build,
                new com.metricplatform.ontology.WarehouseColumns.TableTarget("instance","default_catalog","env_prod","orders"));
        var evidence = new PropertyEvidence("bigint(20)",observation,"MATCHED");
        var delta = new EvidenceUpdate(obj.primaryPropertyId(),null,evidence);
        var report = new BindingReport(build,1,List.of(),2,"policy");
        assertTrue(store.saveObservedReport(scope,report,new ObservationWrite(b,List.of(delta),() -> {})));
        assertFalse(store.saveObservedReport(scope,report,new ObservationWrite(a,List.of(delta),() -> fail())));
        assertEquals(evidence,store.read(scope).objects().getFirst().properties().getFirst().evidence());
        assertEquals(1,store.read(scope).revision());
    }

    @Test
    void unsuccessfulObservationFromStaleGraphCannotEraseLastSuccess() {
        var obj=object("订单"); var build=UUID.randomUUID(); store.createObject(scope,0,obj);
        var report=new BindingReport(build,1,List.of(),2,"active");
        var before=new PropertyEvidence("bigint",null,"MATCHED");
        var table=new com.metricplatform.ontology.WarehouseColumns.TableTarget("instance","default_catalog","env_prod","orders");
        var fresh=new PropertyEvidence("bigint",new ColumnObservation("bigint(20)","2026-11-24T12:00:00Z",build,table),"MATCHED");
        var id=obj.primaryPropertyId();
        assertTrue(store.saveObservedReport(scope,report,new ObservationWrite(store.beginObservation(scope),
                List.of(new EvidenceUpdate(id,null,before)),()->{})));
        assertTrue(store.saveObservedReport(scope,report,new ObservationWrite(store.beginObservation(scope),
                List.of(new EvidenceUpdate(id,before,fresh)),()->{})));
        var failed=new PropertyEvidence("bigint",null,"METADATA_TIMEOUT");
        var stale=new ObservationWrite(store.beginObservation(scope),List.of(new EvidenceUpdate(id,before,failed)),()->{});
        assertThrows(OntologyException.class,()->store.saveObservedReport(scope,report,stale));
        assertEquals(fresh,store.read(scope).objects().getFirst().properties().getFirst().evidence());
    }

    @Test
    void deploymentSwitchRollsBackAcceptedTypeAndGraphEdit() {
        var obj=object("订单"); var build=UUID.randomUUID(); store.createObject(scope,0,obj);
        var report=new BindingReport(build,1,List.of(),2,"active");
        var initial=new PropertyEvidence("bigint",null,"MATCHED");
        var first=new EvidenceUpdate(obj.primaryPropertyId(),null,initial);
        assertTrue(store.saveObservedReport(scope,report,new ObservationWrite(store.beginObservation(scope),List.of(first),()->{})));
        var migrated=new EvidenceUpdate(obj.primaryPropertyId(),initial,new PropertyEvidence("varchar(32)",null,"MATCHED"));
        var write=new ObservationWrite(store.beginObservation(scope),List.of(migrated),
                () -> { throw new OntologyException(CONFLICT,"部署已切换"); });
        assertThrows(OntologyException.class,() -> store.writeObservedObject(scope,1,obj,false,build,java.util.Set.of(),write));
        assertEquals(initial,store.read(scope).objects().getFirst().properties().getFirst().evidence());
        assertEquals(1,store.read(scope).revision());
        assertEquals(report,store.readReport(scope,build,1).orElseThrow());
    }

    @Test
    void reportsAreKeyedByBuildAndConditionalOnCurrentRevision() {
        var object = object("订单");
        store.createObject(scope, 0, object);
        var finding = new Finding("OBJECT", object.id(), BindingStatus.COMPATIBLE, "MATCH");
        var first = new BindingReport(UUID.randomUUID(), 1, List.of(finding));
        var next = new BindingReport(UUID.randomUUID(), 1, List.of());
        assertTrue(store.saveReport(scope, next));
        assertTrue(store.saveReport(scope, first));
        assertEquals(next, store.readReport(scope, next.buildId(), 1).orElseThrow());
        assertEquals(first, store.readReport(scope, first.buildId(), 1).orElseThrow());
        store.deleteObject(scope, 1, object.id());
        assertFalse(store.saveReport(scope, first));
        assertTrue(store.readReport(scope, first.buildId(), 1).isEmpty());
        assertEquals(2, store.read(scope).revision());
    }
}
