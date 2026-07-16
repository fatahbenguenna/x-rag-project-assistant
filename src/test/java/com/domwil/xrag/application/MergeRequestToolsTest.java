package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.MergeRequestMeta;
import com.domwil.xrag.domain.port.MergeRequestRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MergeRequestToolsTest {

    private final MergeRequestRepository repository = mock(MergeRequestRepository.class);
    private final MergeRequestTools tools = new MergeRequestTools(repository,
            Map.of("pos", List.of("caisse"), "kds", List.of("cuisine")));

    private static MergeRequestMeta mr(long iid, String title) {
        return new MergeRequestMeta("gitlab:42:" + iid, "kds", iid, title, "description",
                "merged", "alice", "feature", "main", "https://gitlab.com/mr/" + iid,
                List.of(), List.of(), Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"), null);
    }

    @Test
    void rechercheLesMrsParSujetEtLesFormate() {
        when(repository.search(any(), anyInt()))
                .thenReturn(List.of(mr(101, "feat: notification caisse vers KDS"),
                        mr(102, "fix: timeout KDS")));

        String result = tools.searchMergeRequests("KDS caisse", null);

        assertThat(result).contains("!101").contains("notification caisse vers KDS")
                .contains("!102").contains("timeout KDS");
    }

    @Test
    void messageExpliciteQuandAucuneMrNeCorrespond() {
        when(repository.search(any(), anyInt())).thenReturn(List.of());

        assertThat(tools.searchMergeRequests("inexistant", null))
                .contains("Aucune").contains("inexistant");
    }

    @Test
    void limiteParDefautA20QuandNonPrecisee() {
        when(repository.search(any(), anyInt())).thenReturn(List.of());

        tools.searchMergeRequests("KDS", null);

        verify(repository).search(any(), eq(20));
    }

    @Test
    void plafonneLaLimiteA50() {
        when(repository.search(any(), anyInt())).thenReturn(List.of());

        tools.searchMergeRequests("KDS", 999);

        verify(repository).search(any(), eq(50));
    }

    @Test
    void etendUnTermeMetierVersSonSynonymeCode() {
        // « caisse » doit s'étendre au concept contenant aussi « pos » (nom code de l'app).
        List<List<String>> concepts = tools.toConcepts("caisse");

        assertThat(concepts).hasSize(1);
        assertThat(concepts.getFirst()).contains("pos", "caisse");
    }

    @Test
    void ecarteLesMotsVidesEtDedupliqueLesConcepts() {
        // « la », « et » écartés ; « caisse » répété = un seul concept.
        List<List<String>> concepts = tools.toConcepts("les MRs sur la caisse et la caisse");

        long conceptsCaisse = concepts.stream().filter(c -> c.contains("caisse")).count();
        assertThat(conceptsCaisse).isEqualTo(1);
        assertThat(concepts).noneSatisfy(c -> assertThat(c).contains("la"));
    }

    @Test
    void unMotSansSynonymeDevientUnConceptSingleton() {
        assertThat(tools.toConcepts("multitenant")).containsExactly(List.of("multitenant"));
    }
}
