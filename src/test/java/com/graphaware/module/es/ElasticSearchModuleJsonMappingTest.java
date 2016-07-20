package com.graphaware.module.es;

import com.graphaware.integration.es.test.EmbeddedElasticSearchServer;
import com.graphaware.integration.es.test.JestElasticSearchClient;
import com.graphaware.module.es.mapping.JsonFileMapping;
import com.graphaware.module.es.mapping.Mapping;
import com.graphaware.module.es.util.ServiceLoader;
import com.graphaware.module.es.util.TestUtil;
import com.graphaware.module.uuid.UuidConfiguration;
import com.graphaware.module.uuid.UuidModule;
import com.graphaware.runtime.GraphAwareRuntime;
import com.graphaware.runtime.GraphAwareRuntimeFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.test.TestGraphDatabaseFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class ElasticSearchModuleJsonMappingTest extends ElasticSearchModuleIntegrationTest {

    @Before
    public void setUp() {
        esServer = new EmbeddedElasticSearchServer();
        esServer.start();
        esClient = new JestElasticSearchClient(HOST, PORT);

    }

    @After
    public void tearDown() {
        database.shutdown();
        esServer.stop();
        esClient.shutdown();
    }

    @Test
    public void testBasicJsonMappingModuleBootstrap() {
        database = new TestGraphDatabaseFactory().newImpermanentDatabase();

        GraphAwareRuntime runtime = GraphAwareRuntimeFactory.createRuntime(database);
        runtime.registerModule(new UuidModule("UUID", UuidConfiguration.defaultConfiguration(), database));

        Mapping mapping = ServiceLoader.loadMapping("com.graphaware.module.es.mapping.JsonFileMapping");
        Map<String, String> config = new HashMap<>();
        config.put("file", "integration/mapping-basic.json");
        mapping.configure(config);
        System.out.println(mapping.getKeyProperty());

        configuration = ElasticSearchConfiguration.defaultConfiguration()
                .withMapping(mapping)
                .withUri(HOST)
                .withPort(PORT);

        assertEquals("uuid", configuration.getMapping().getKeyProperty());
    }

    @Test
    public void testBasicJsonMappingReplication() {
        database = new TestGraphDatabaseFactory().newImpermanentDatabase();

        GraphAwareRuntime runtime = GraphAwareRuntimeFactory.createRuntime(database);
        runtime.registerModule(new UuidModule("UUID", UuidConfiguration.defaultConfiguration().withUuidProperty("uuid"), database));

        JsonFileMapping mapping = (JsonFileMapping) ServiceLoader.loadMapping("com.graphaware.module.es.mapping.JsonFileMapping");
        Map<String, String> config = new HashMap<>();
        config.put("file", "integration/mapping-basic.json");
        mapping.configure(config);

        configuration = ElasticSearchConfiguration.defaultConfiguration()
                .withMapping(mapping)
                .withUri(HOST)
                .withPort(PORT);

        runtime.registerModule(new ElasticSearchModule("ES", new ElasticSearchWriter(configuration), configuration));

        runtime.start();
        runtime.waitUntilStarted();

        writeSomePersons();
        TestUtil.waitFor(500);
        verifyEsReplicationForNodeWithLabels("Person", mapping.getDefinition().getDefaults().getIndex(), "persons", mapping.getDefinition().getDefaults().getKeyProperty());
    }

    protected void verifyEsReplicationForNodeWithLabels(String label, String index, String type, String keyProperty) {
        try (Transaction tx = database.beginTx()) {
            database.findNodes(Label.label(label)).stream().forEach(n -> {

                new Neo4jElasticVerifier(database, configuration, esClient).verifyEsReplication(n, index, type, keyProperty);
            });
            tx.success();
        }
    }

    protected void writeSomePersons() {
        //tx1
        database.execute("CREATE (p:Person {firstName:'Michal', lastName:'Bachman', age:30})-[:WORKS_FOR {since:2013, role:'MD'}]->(c:Company {name:'GraphAware', est: 2013})");

        //tx2
        database.execute("MATCH (ga:Company {name:'GraphAware'}) CREATE (p:Person {firstName:'Adam', lastName:'George'})-[:WORKS_FOR {since:2014}]->(ga)");

        //tx3
        try (Transaction tx = database.beginTx()) {
            database.execute("MATCH (ga:Company {name:'GraphAware'}) CREATE (p:Person {firstName:'Daniela', lastName:'Daniela'})-[:WORKS_FOR]->(ga)");
            database.execute("MATCH (p:Person {name:'Michal'}) SET p.age=31");
            database.execute("MATCH (p:Person {name:'Adam'})-[r]-() DELETE p,r");
            database.execute("MATCH (p:Person {name:'Michal'})-[r:WORKS_FOR]->() REMOVE r.role");
            tx.success();
        }
    }

}
