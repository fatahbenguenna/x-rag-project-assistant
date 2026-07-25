package com.domwil.xrag.adapter.out.rerank;

import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.port.ChunkReranker;

import java.util.List;

/** Passe-plat (reranking désactivé) : conserve l'ordre du retrieval et tronque à topK. */
public final class NoOpChunkReranker implements ChunkReranker {

    @Override
    public int candidatePoolSize(int topK) {
        return topK; // aucun élargissement : on récupère directement le nombre final
    }

    @Override
    public List<ScoredChunk> rerank(String question, List<ScoredChunk> candidates, int topK) {
        return candidates.size() <= topK ? candidates : List.copyOf(candidates.subList(0, topK));
    }
}
