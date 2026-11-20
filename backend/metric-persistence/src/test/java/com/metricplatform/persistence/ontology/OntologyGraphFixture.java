package com.metricplatform.persistence.ontology;

import com.metricplatform.ontology.OntologyContracts.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

abstract class OntologyGraphFixture {
    static GenericContainer<?> graph;
    static Driver driver;
    static Neo4jOntologyStore store;
    final Scope scope = new Scope("tenant", UUID.randomUUID());

    @BeforeAll
    static void start() {
        String password = UUID.randomUUID().toString();
        graph = new GenericContainer<>("neo4j:2026.10.1")
                .withEnv("NEO4J_AUTH", "neo4j/" + password)
                .withExposedPorts(7687)
                .waitingFor(Wait.forLogMessage(".*Started\\..*\\n", 1))
                .withStartupTimeout(Duration.ofMinutes(2));
        graph.start();
        driver = GraphDatabase.driver("bolt://" + graph.getHost() + ":" + graph.getMappedPort(7687),
                AuthTokens.basic("neo4j", password));
        store = new Neo4jOntologyStore(driver);
    }

    @AfterAll
    static void stop() {
        if (driver != null) driver.close();
        if (graph != null) graph.stop();
    }

    static ObjectType object(String name) {
        var key = new Property(UUID.randomUUID(), "编号", "id", "INTEGER");
        return new ObjectType(UUID.randomUUID(), name, "", UUID.randomUUID(), key.id(), List.of(key));
    }
}
