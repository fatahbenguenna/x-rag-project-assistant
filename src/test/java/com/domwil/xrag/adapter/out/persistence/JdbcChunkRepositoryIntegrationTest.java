package com.domwil.xrag.adapter.out.persistence;

import com.domwil.xrag.domain.model.Chunk;
import com.domwil.xrag.domain.model.UnattachedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcChunkRepositoryIntegrationTest extends PostgresIntegrationSupport {

    private final JdbcChunkRepository chunks = new JdbcChunkRepository(jdbc);

    /** Vecteur unitaire : 1.0 sur une dimension — jamais nul (cosine NaN). */
    private static float[] unit(int dimension) {
        float[] v = new float[1024];
        v[dimension] = 1f;
        return v;
    }

    private static Chunk chunk(String id, String path, String content, Set<String> nodeIds, int dim) {
        return new Chunk(id, "confluence", "fps", path, 0, "titre " + id, content, null,
                nodeIds, unit(dim), "v1");
    }

    @Test
    void unReUpsertPreserveLesTopicsMaisSuitLesRattachementsDeterministes() {
        // LA non-régression de la revue (H6) : l'érosion silencieuse des topics au re-embed
        chunks.upsert(List.of(chunk("c1", "p1", "contenu", Set.of("page:1"), 0)));
        chunks.attachToNodes("confluence", "p1", Set.of("topic:kafka"));

        chunks.upsert(List.of(chunk("c1", "p1", "contenu modifié", Set.of("page:2"), 0)));

        List<String> nodeIds = jdbc.queryForList(
                "SELECT unnest(node_ids) FROM rag_chunks WHERE id = 'c1'", String.class);
        assertThat(nodeIds).containsExactlyInAnyOrder("page:2", "topic:kafka");
    }

    @Test
    void hybridSearchTrouveParLeCanalLexicalEnOuEtRespecteLeBoost() {
        // A matche « retry » seul : l'AND historique (retry+inexistant) l'aurait raté
        chunks.upsert(List.of(
                chunk("a", "pa", "la strategie de retry du kiosque", Set.of(), 1),
                chunk("b", "pb", "rien a voir ici", Set.of("node:boost"), 2)));

        var lexical = chunks.hybridSearch(unit(3), "retry motinexistant", Set.of(), null, 5, 0.3);
        assertThat(lexical).anyMatch(c -> c.id().equals("a"));

        // boost : embeddings orthogonaux à la question (cos identiques) → seul le boost départage
        var boosted = chunks.hybridSearch(unit(3), "zzz", Set.of("node:boost"), null, 2, 0.3);
        assertThat(boosted.getFirst().id()).isEqualTo("b");
    }

    @Test
    void keywordSearchEnOuEtRobusteAuVide() {
        chunks.upsert(List.of(chunk("k1", "pk", "configuration du webhook kds", Set.of(), 4)));

        assertThat(chunks.keywordSearch("webhook motabsent", null, 5))
                .anyMatch(c -> c.id().equals("k1"));
        assertThat(chunks.keywordSearch("?? !!", null, 5)).isEmpty();
    }

    @Test
    void documentsNeedingTopicsExposeLesNoeudsExistantsNonTopic() {
        chunks.upsert(List.of(
                chunk("d1", "pd1", "sans topic", Set.of("page:9", "project:fps"), 5),
                chunk("d2", "pd2", "avec topic", Set.of("page:8", "topic:deja"), 6)));

        List<UnattachedDocument> needing = chunks.documentsNeedingTopics(List.of("confluence"), 10);

        assertThat(needing).hasSize(1);
        assertThat(needing.getFirst().path()).isEqualTo("pd1");
        // les non-topic sont exposés (le project: y figure : c'est le service/dehub qui l'exclut des arêtes)
        assertThat(needing.getFirst().existingNodeIds()).contains("page:9");
    }
}
