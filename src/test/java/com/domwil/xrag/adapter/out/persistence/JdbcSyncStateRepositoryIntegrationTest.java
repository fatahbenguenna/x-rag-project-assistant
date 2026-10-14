package com.domwil.xrag.adapter.out.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Curseur de sync : un échec n'avance jamais last_sync, mais son statut reste visible. */
class JdbcSyncStateRepositoryIntegrationTest extends PostgresIntegrationSupport {

    private final JdbcSyncStateRepository syncState = new JdbcSyncStateRepository(jdbc);

    @Test
    void unEchecGardeLeCurseurDeLaDerniereSyncReussie() {
        Instant lastSuccess = Instant.parse("2026-10-01T00:00:00Z");
        syncState.record("jira", lastSuccess, "OK (3/3 indexés)");

        syncState.recordFailure("jira", "ERREUR 401 Unauthorized");

        assertThat(syncState.lastSync("jira")).contains(lastSuccess);
        assertThat(jdbc.queryForObject("SELECT last_status FROM sync_state WHERE source = 'jira'", String.class))
                .isEqualTo("ERREUR 401 Unauthorized");
    }

    @Test
    void unPremierRunEnEchecEstVisibleSansPoserDeCurseur() {
        syncState.recordFailure("confluence", "ERREUR 401 Unauthorized");

        // Pas de curseur : la sync suivante repart de zéro ; la ligne reste lisible par le dashboard.
        assertThat(syncState.lastSync("confluence")).isEmpty();
        assertThat(new JdbcIndexingStatusRepository(jdbc).metrics().sources())
                .singleElement()
                .satisfies(state -> {
                    assertThat(state.lastSync()).isNull();
                    assertThat(state.lastStatus()).isEqualTo("ERREUR 401 Unauthorized");
                });

        syncState.record("confluence", Instant.parse("2026-10-02T00:00:00Z"), "OK (1/1 indexés)");
        assertThat(syncState.lastSync("confluence")).contains(Instant.parse("2026-10-02T00:00:00Z"));
    }
}
