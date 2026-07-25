package com.domwil.xrag.adapter.out.persistence;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Socle des tests d'intégration JDBC ciblés (revue 2026-07, M7-tests) : un conteneur
 * pgvector UNIQUE par JVM de test (pattern singleton, nettoyé par ryuk), schéma appliqué
 * par les VRAIS changelogs Liquibase, tables tronquées avant chaque test. Volontairement
 * SANS slice Spring : les repositories sont instanciés directement — zéro contexte, zéro
 * magie, exactement le SQL qui tourne en production.
 *
 * <p>Pourquoi ces tests : la convention « pas de test d'intégration JDBC » a laissé
 * passer plusieurs bugs SQL réels (String.formatted sur LIKE 'topic:%', purge ASCII des
 * accents, NPE de binding). Ils couvrent les requêtes complexes, pas les CRUD triviaux.
 * Sans Docker disponible, les tests sont ignorés proprement (assumption JUnit).
 */
public abstract class PostgresIntegrationSupport {

    private static final boolean DOCKER_AVAILABLE = DockerClientFactory.instance().isDockerAvailable();

    private static PostgreSQLContainer<?> postgres;
    protected static JdbcTemplate jdbc;

    @BeforeAll
    static void startPostgres() throws Exception {
        if (!DOCKER_AVAILABLE) {
            // Surefire affiche « Tests run: 0 » SANS compter de skip quand l'assumption
            // échoue en @BeforeAll : sans ce message, les 9 tests disparaîtraient en
            // silence (démon absent, ou API refusée — voir api.version dans le pom).
            System.err.println("[ATTENTION] Docker indisponible ou API refusée : les tests "
                    + "d'intégration JDBC sont IGNORÉS. Si Docker tourne, vérifier "
                    + "-Dapi.version (pom.xml, défaut 1.44 — Docker < 25 : -Dapi.version=1.43).");
        }
        assumeTrue(DOCKER_AVAILABLE, "Docker indisponible : tests d'intégration JDBC ignorés");
        if (jdbc != null) {
            return; // conteneur singleton déjà démarré par une classe précédente
        }
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
        postgres.start();

        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        try (Connection connection = dataSource.getConnection()) {
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase("db/changelog/db.changelog-master.xml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        }
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void truncateAll() {
        jdbc.execute("TRUNCATE rag_chunks, graph_edges, graph_nodes, entity_aliases, "
                + "merge_requests, sync_state CASCADE");
    }
}
