package com.domwil.xrag.domain.model;

import java.util.Set;

/** Chunk vectorisé prêt à être upserté dans rag_chunks. */
public record Chunk(
        String id,                // "source:path:chunk_index" (clé stable pour l'upsert)
        String source,
        String project,
        String path,
        int chunkIndex,
        String title,
        String content,
        String url,
        Set<String> nodeIds,      // nœuds du graphe rattachés (pont RAG <-> graphe)
        float[] embedding,
        String indexedVersion
) {

    public Chunk {
        nodeIds = nodeIds == null ? Set.of() : Set.copyOf(nodeIds);
    }
}
