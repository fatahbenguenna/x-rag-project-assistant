package com.domwil.xrag.domain.port;

import com.domwil.xrag.domain.model.Chunk;
import com.domwil.xrag.domain.model.ScoredChunk;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Port des chunks vectorisés (table rag_chunks) : recherche + ingestion upsert only. */
public interface ChunkRepository {

    void upsert(Collection<Chunk> chunks);

    /** Version indexée d'un document ({@code null} si jamais indexé) — évite le re-embedding inutile. */
    Optional<String> indexedVersion(String source, String path);

    /** Purge les chunks du document absents de {@code keepIds} (document raccourci). */
    void deleteOtherChunksOf(String source, String path, Collection<String> keepIds);

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
