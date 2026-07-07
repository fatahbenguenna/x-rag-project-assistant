package com.domwil.xrag.domain.port;

import java.time.Instant;
import java.util.Optional;

/** Curseur de sync incrémentale par source (table sync_state). */
public interface SyncStateRepository {

    Optional<Instant> lastSync(String source);

    void record(String source, Instant syncedAt, String status);
}
