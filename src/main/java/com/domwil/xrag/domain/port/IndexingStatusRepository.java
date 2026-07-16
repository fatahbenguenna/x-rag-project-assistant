package com.domwil.xrag.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Lecture seule des métriques d'indexation persistées, pour le dashboard de
 * monitoring : chunks par source, tailles du graphe, MRs et dernière sync par source.
 */
public interface IndexingStatusRepository {

    /** Agrège en une passe les compteurs persistés servant le dashboard. */
    PersistedMetrics metrics();

    /** Compteurs persistés (tables {@code rag_chunks}, {@code graph_*}, {@code merge_requests}, {@code sync_state}). */
    record PersistedMetrics(
            long totalChunks,
            Map<String, Long> chunksBySource,
            long graphNodes,
            long graphEdges,
            long mergeRequests,
            List<SourceSyncState> sources) {
    }

    /** Dernière synchronisation connue d'une source (table {@code sync_state}). */
    record SourceSyncState(
            String source,
            Instant lastSync,
            String lastStatus) {
    }
}
