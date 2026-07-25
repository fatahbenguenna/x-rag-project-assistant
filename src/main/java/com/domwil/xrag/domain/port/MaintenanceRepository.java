package com.domwil.xrag.domain.port;

import java.util.Map;

/** Opérations d'entretien du batch nocturne (réconciliation, VACUUM). */
public interface MaintenanceRepository {

    /** Supprime les nœuds sans arête ni chunk rattaché. Retourne le nombre purgé. */
    int purgeOrphanNodes();

    /**
     * Supprime les nœuds TOPIC (avec leurs arêtes et alias) qu'aucun chunk ne référence
     * plus — les topics sont dérivés et jetables, le prochain enrichissement recrée ce
     * qui manque. Sans ce GC, graph_nodes/entity_aliases croissent de façon monotone
     * (purgeOrphanNodes ne les atteint jamais : arête + alias systématiques).
     */
    int purgeUnreferencedTopics();

    /**
     * Recâble les arêtes des topics : supprime les arêtes TOPIC→PROJECT (l'étoile autour
     * du hub rendait le voisinage non discriminant, revue 2026-07 H2) et crée les arêtes
     * TOPIC→document (page/issue/class) dérivées des node_ids des chunks. Idempotent —
     * répare aussi le stock des instances existantes à chaque batch nocturne.
     */
    int dehubTopicEdges();

    /**
     * Hygiène des topics (revue 2026-07, M2), idempotente, exécutée au batch nocturne :
     * PURGE complète des topics au slug non alphanumérique-latin (bruit CJK/symboles —
     * nœud, arêtes, alias, et retrait des node_ids des chunks) ; NEUTRALISATION des
     * topics génériques (rattachés à {@code >= 40} documents) — alias et arêtes
     * supprimés, le nœud restant en marqueur passif dans les node_ids pour empêcher
     * leur re-création par l'enrichissement. Retourne le nombre de topics traités.
     */
    int purgeNoisyTopics();

    /** VACUUM ANALYZE des tables chaudes (l'index HNSW n'est jamais reconstruit). */
    void vacuumAnalyze();

    /** Compteurs pour le statut/smoke test : chunks par source, nœuds, arêtes, MRs. */
    Map<String, Object> stats();
}
