package com.domwil.xrag.domain.port;

import java.time.Instant;
import java.util.Optional;

/** Curseur de sync incrémentale par source (table sync_state). */
public interface SyncStateRepository {

    Optional<Instant> lastSync(String source);

    /** Sync réussie : avance le curseur et enregistre le statut. */
    void record(String source, Instant syncedAt, String status);

    /**
     * Sync en échec : enregistre le statut SANS avancer le curseur. Avancer {@code last_sync}
     * sur un échec (401, 410, réseau) faisait sauter la fenêtre manquée à la sync suivante :
     * les documents modifiés entre la dernière sync réussie et l'échec n'étaient jamais
     * ré-indexés. Sans ligne existante (premier run en échec), rien n'est écrit : la sync
     * suivante repart de zéro.
     */
    void recordFailure(String source, String status);
}
