package com.domwil.xrag.domain.port;

import java.util.Map;

/** Opérations d'entretien du batch nocturne (réconciliation, VACUUM). */
public interface MaintenanceRepository {

    /** Supprime les nœuds sans arête ni chunk rattaché. Retourne le nombre purgé. */
    int purgeOrphanNodes();

    /** VACUUM ANALYZE des tables chaudes (l'index HNSW n'est jamais reconstruit). */
    void vacuumAnalyze();

    /** Compteurs pour le statut/smoke test : chunks par source, nœuds, arêtes, MRs. */
    Map<String, Object> stats();
}
