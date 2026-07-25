package com.domwil.xrag.adapter.out.persistence;

import com.domwil.xrag.domain.model.MergeRequestMeta;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** La recherche MR par concepts : OU, frontière de mot, pondération titre > corps. */
class JdbcMergeRequestRepositoryIntegrationTest extends PostgresIntegrationSupport {

    private final JdbcMergeRequestRepository mergeRequests = new JdbcMergeRequestRepository(jdbc);

    private static MergeRequestMeta mr(long iid, String title, String description) {
        return new MergeRequestMeta("gitlab:1:" + iid, "fps", iid, title, description, "merged",
                "alice", "feat", "main", null, List.of(), List.of(),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-0" + (iid % 9 + 1) + "T00:00:00Z"), null);
    }

    @Test
    void searchMatcheAFrontiereDeMotEtPonderezLeTitre() {
        mergeRequests.upsert(List.of(
                mr(1, "fix(fps-pos): écran bloqué", "détail"),
                mr(2, "chore: composer update", "rien"),          // « composer » ≠ mot « pos »
                mr(3, "docs: suivi général", "mentionne pos dans le corps")));

        var results = mergeRequests.search(List.of(List.of("pos", "caisse")), 10);

        assertThat(results).extracting(MergeRequestMeta::iid)
                .containsExactly(1L, 3L); // titre (poids 3) avant corps (poids 1) ; « composer » exclu
    }

    @Test
    void searchSansConceptRendVide() {
        mergeRequests.upsert(List.of(mr(1, "titre", "desc")));
        assertThat(mergeRequests.search(List.of(), 10)).isEmpty();
    }
}
