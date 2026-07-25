package com.domwil.xrag.domain.port;

import com.domwil.xrag.domain.model.ScoredChunk;

import java.util.List;

/**
 * Reclassement des candidats du retrieval par pertinence question↔passage
 * (cross-encoder) — action M-7 de la revue 2026-07. Le retrieval hybride fournit un
 * vivier élargi ({@link #candidatePoolSize}), le reranker en extrait les {@code topK}
 * réellement pertinents. Implémentation par défaut : passe-plat quand le reranking est
 * désactivé (pattern Null Object — jamais de {@code null}).
 */
public interface ChunkReranker {

    /** Nombre de candidats à récupérer en amont : élargi (ex. 40) si actif, sinon {@code topK}. */
    int candidatePoolSize(int topK);

    /** Les {@code topK} candidats les plus pertinents, réordonnés. Jamais {@code null}. */
    List<ScoredChunk> rerank(String question, List<ScoredChunk> candidates, int topK);
}
