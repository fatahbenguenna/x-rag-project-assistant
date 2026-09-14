package com.domwil.xrag.adapter.out.persistence;

import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.GraphNode;
import com.domwil.xrag.domain.model.MergeRequestMeta;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Réconciliation des MRs ouvertes reconstituées depuis git (mode sources.git) : suppression
 * ciblée des {@code opened} disparues, purge de leurs nœuds, garde {@code iid > 0} du lookup.
 */
class MergeRequestReconciliationIntegrationTest extends PostgresIntegrationSupport {

    private final JdbcMergeRequestRepository mergeRequests = new JdbcMergeRequestRepository(jdbc);
    private final JdbcGraphRepository graph = new JdbcGraphRepository(jdbc, new ObjectMapper());

    private static MergeRequestMeta mr(String id, long iid, String state) {
        return new MergeRequestMeta(id, "fps", iid, "titre " + id, "", state, "bob", "feat", "main", null,
                List.of(), List.of(), Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"), null);
    }

    @Test
    void supprimeSeulementLesOuvertesAbsentesDeLInstantane() {
        mergeRequests.upsert(List.of(
                mr("git-branch:fps:feat/a", 0, "opened"),
                mr("git-branch:fps:feat/b", 0, "opened"),
                mr("git:fps:153", 153, "merged")));

        List<String> removed = mergeRequests.deleteOpenedNotIn(Set.of("git-branch:fps:feat/a"));

        assertThat(removed).containsExactly("git-branch:fps:feat/b");
        assertThat(mergeRequests.count("all")).isEqualTo(2);
        assertThat(mergeRequests.count("opened")).isEqualTo(1);
        assertThat(mergeRequests.deleteOpenedNotIn(Set.of())).containsExactly("git-branch:fps:feat/a");
    }

    @Test
    void purgeLesNoeudsAvecLeursAretesEtAlias() {
        graph.upsertNodes(List.of(
                GraphNode.of("mr:git-branch:fps:feat/b", GraphNode.Types.MR, "b"),
                GraphNode.of("project:fps", GraphNode.Types.PROJECT, "fps"),
                GraphNode.of("issue:PASS-1", GraphNode.Types.ISSUE, "PASS-1")));
        graph.upsertEdges(List.of(
                GraphEdge.of("mr:git-branch:fps:feat/b", "project:fps", GraphEdge.Types.REFERENCES),
                GraphEdge.of("mr:git-branch:fps:feat/b", "issue:PASS-1", GraphEdge.Types.REFERENCES)));
        graph.upsertAliases(Map.of("featb", "mr:git-branch:fps:feat/b"));

        graph.deleteNodes(List.of("mr:git-branch:fps:feat/b"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM graph_nodes", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM graph_edges", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entity_aliases", Long.class)).isZero();
        graph.deleteNodes(List.of()); // sans effet, sans erreur
    }

    @Test
    void leLookupParNumeroIgnoreLesMrsSansNumero() {
        mergeRequests.upsert(List.of(mr("git:fps:commits:abc", 0, "merged"), mr("git:fps:7", 7, "merged")));

        assertThat(mergeRequests.findByIid(0)).isEmpty();
        assertThat(mergeRequests.findByIid(7)).map(MergeRequestMeta::id).contains("git:fps:7");
    }
}
