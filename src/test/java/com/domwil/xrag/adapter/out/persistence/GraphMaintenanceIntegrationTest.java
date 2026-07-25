package com.domwil.xrag.adapter.out.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Requêtes complexes du graphe : CTE récursive anti-hub, recâblage, hygiène des topics. */
class GraphMaintenanceIntegrationTest extends PostgresIntegrationSupport {

    private final JdbcGraphSearchRepository graphSearch = new JdbcGraphSearchRepository(jdbc);
    private final JdbcMaintenanceRepository maintenance = new JdbcMaintenanceRepository(jdbc);

    private void node(String id, String type) {
        jdbc.update("INSERT INTO graph_nodes (id, type, name) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                id, type, id);
    }

    private void edge(String src, String dst) {
        jdbc.update("INSERT INTO graph_edges (src, dst, type) VALUES (?, ?, 'REFERENCES') "
                + "ON CONFLICT DO NOTHING", src, dst);
    }

    private void chunkWithNodes(String id, String path, String... nodeIds) {
        jdbc.update("INSERT INTO rag_chunks (id, source, path, chunk_index, content, node_ids) "
                + "VALUES (?, 'confluence', ?, 0, 'contenu', ?::text[])",
                id, path, PgArrays.textArray(List.of(nodeIds)));
    }

    @Test
    void leVoisinageEstDeterministeEtNeTraversePasLesHubs() {
        // un hub (degré 51 > seuil 50) relié à une graine, et un satellite derrière le hub
        node("seed", "PAGE");
        node("hub", "PROJECT");
        node("derriere-hub", "PAGE");
        edge("seed", "hub");
        edge("hub", "derriere-hub");
        for (int i = 0; i < 50; i++) {
            node("filler-" + i, "CLASS");
            edge("hub", "filler-" + i);
        }
        // un chemin normal profondeur 2
        node("voisin", "PAGE");
        node("voisin-du-voisin", "PAGE");
        edge("seed", "voisin");
        edge("voisin", "voisin-du-voisin");

        var first = graphSearch.neighborhood(Set.of("seed"), 2);
        var second = graphSearch.neighborhood(Set.of("seed"), 2);

        assertThat(first.nodeIds()).contains("hub", "voisin", "voisin-du-voisin");
        // le hub est ATTEINT mais pas TRAVERSÉ : ce qui est derrière lui reste invisible
        assertThat(first.nodeIds()).doesNotContain("derriere-hub");
        // déterminisme (l'ancien LIMIT sans ORDER BY échantillonnait arbitrairement)
        assertThat(first.nodeIds()).isEqualTo(second.nodeIds());
    }

    @Test
    void purgeNoisyTopicsGardeLesAccentsPurgeLeNonLatinEtNeutraliseLesGeneriques() {
        node("topic:sécurité", "TOPIC");
        node("topic:api配置", "TOPIC");
        node("topic:generique", "TOPIC");
        jdbc.update("INSERT INTO entity_aliases (alias, node_id) VALUES "
                + "('sécurité', 'topic:sécurité'), ('api配置', 'topic:api配置'), "
                + "('generique', 'topic:generique')");
        chunkWithNodes("c-fr", "p-fr", "topic:sécurité", "topic:api配置");
        for (int i = 0; i < 40; i++) {
            chunkWithNodes("c-gen-" + i, "p-gen-" + i, "topic:generique");
        }

        maintenance.purgeNoisyTopics();

        // les accents français SURVIVENT (le bug ASCII les détruisait — revue adversariale)
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM graph_nodes WHERE id='topic:sécurité'", Integer.class)).isEqualTo(1);
        // le non-latin est purgé, y compris des node_ids
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM graph_nodes WHERE id='topic:api配置'", Integer.class)).isZero();
        assertThat(jdbc.queryForList(
                "SELECT unnest(node_ids) FROM rag_chunks WHERE id='c-fr'", String.class))
                .containsExactly("topic:sécurité");
        // le générique (df=40) est neutralisé : plus d'alias, nœud conservé en marqueur
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM entity_aliases WHERE node_id='topic:generique'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM graph_nodes WHERE id='topic:generique'", Integer.class)).isEqualTo(1);
        // la restauration auto-réparatrice a re-créé l'alias du topic légitime si absent
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM entity_aliases WHERE node_id='topic:sécurité'", Integer.class)).isEqualTo(1);
    }

    @Test
    void dehubRecableVersLesDocumentsSansProjectNiTopicsNeutralises() {
        node("topic:actif", "TOPIC");
        node("topic:neutralise", "TOPIC");
        node("page:doc", "PAGE");
        node("project:fps", "PROJECT");
        jdbc.update("INSERT INTO entity_aliases (alias, node_id) VALUES ('actif', 'topic:actif')");
        // pas d'alias pour topic:neutralise = neutralisé
        chunkWithNodes("c1", "p1", "topic:actif", "topic:neutralise", "page:doc", "project:fps");

        maintenance.dehubTopicEdges();

        List<String> edges = jdbc.queryForList(
                "SELECT src || '->' || dst FROM graph_edges", String.class);
        assertThat(edges).containsExactly("topic:actif->page:doc");
        // ni vers project:, ni depuis le topic neutralisé (churn nocturne — revue adversariale)
    }
}
