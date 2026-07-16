package com.domwil.xrag.application;

import com.domwil.xrag.application.IndexingProgressTracker.ProgressSnapshot;
import com.domwil.xrag.domain.port.IndexingStatusRepository;
import com.domwil.xrag.domain.port.IndexingStatusRepository.PersistedMetrics;

/**
 * Agrège l'état complet de l'indexation pour le dashboard : métriques persistées
 * (lues en base) et état vivant de l'indexation en cours (en mémoire). Point d'entrée
 * unique de l'endpoint {@code GET /api/admin/indexing-status}.
 */
public class IndexingStatusService {

    private final IndexingStatusRepository repository;
    private final IndexingProgressTracker tracker;

    public IndexingStatusService(IndexingStatusRepository repository, IndexingProgressTracker tracker) {
        this.repository = repository;
        this.tracker = tracker;
    }

    public IndexingStatus current() {
        return new IndexingStatus(repository.metrics(), tracker.snapshot());
    }

    /** Vue complète du dashboard : ce qui est persisté et ce qui tourne en ce moment. */
    public record IndexingStatus(
            PersistedMetrics metrics,
            ProgressSnapshot live) {
    }
}
