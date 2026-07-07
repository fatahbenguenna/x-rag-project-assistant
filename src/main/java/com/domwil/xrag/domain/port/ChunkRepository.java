package com.domwil.xrag.domain.port;

import com.domwil.xrag.domain.model.ScoredChunk;

import java.util.List;
import java.util.Set;

/** Port de recherche dans les chunks vectorisés (table rag_chunks). */
public interface ChunkRepository {

    /**
     * Recherche hybride : similarité vectorielle + full-text (tsvector français),
     * boostée pour les chunks rattachés aux nœuds du sous-graphe.
     *
     * @param embedding    embedding de la question (bge-m3, 1024 dims)
     * @param query        question en texte libre (full-text)
     * @param boostNodeIds nœuds du sous-graphe (boost node_ids && ...)
     * @param project      filtre métadonnées par projet ({@code null} = tous)
     */
    List<ScoredChunk> hybridSearch(float[] embedding, String query, Set<String> boostNodeIds,
                                   String project, int limit);
}
