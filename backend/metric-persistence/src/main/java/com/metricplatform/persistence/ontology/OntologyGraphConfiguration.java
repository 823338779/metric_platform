package com.metricplatform.persistence.ontology;

import com.metricplatform.ontology.OntologyStore;
import java.util.concurrent.TimeUnit;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OntologyGraphConfiguration {
    @Bean(destroyMethod = "close")
    Driver ontologyDriver(@Value("${metric-platform.ontology.uri:bolt://localhost:17687}") String uri,
                          @Value("${metric-platform.ontology.username:neo4j}") String username,
                          @Value("${metric-platform.ontology.password:}") String password) {
        var config = Config.builder()
                .withConnectionTimeout(3, TimeUnit.SECONDS)
                .withConnectionAcquisitionTimeout(5, TimeUnit.SECONDS)
                .withMaxTransactionRetryTime(3, TimeUnit.SECONDS)
                .build();
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
    }

    @Bean
    OntologyStore ontologyStore(Driver ontologyDriver) {
        return new Neo4jOntologyStore(ontologyDriver);
    }
}
