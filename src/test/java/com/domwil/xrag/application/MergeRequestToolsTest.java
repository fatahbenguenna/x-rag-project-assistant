package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.MergeRequestMeta;
import com.domwil.xrag.domain.port.MergeRequestRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MergeRequestToolsTest {

    private final MergeRequestRepository repository = mock(MergeRequestRepository.class);
    private final MergeRequestTools tools = new MergeRequestTools(repository);

    private static MergeRequestMeta mr(long iid, String title) {
        return new MergeRequestMeta("gitlab:42:" + iid, "kds", iid, title, "description",
                "merged", "alice", "feature", "main", "https://gitlab.com/mr/" + iid,
                List.of(), List.of(), Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"), null);
    }

    @Test
    void rechercheLesMrsParSujetEtLesFormate() {
        when(repository.search(eq("KDS caisse"), anyInt()))
                .thenReturn(List.of(mr(101, "feat: notification caisse vers KDS"),
                        mr(102, "fix: timeout KDS")));

        String result = tools.searchMergeRequests("KDS caisse", null);

        assertThat(result).contains("!101").contains("notification caisse vers KDS")
                .contains("!102").contains("timeout KDS");
    }

    @Test
    void messageExpliciteQuandAucuneMrNeCorrespond() {
        when(repository.search(eq("inexistant"), anyInt())).thenReturn(List.of());

        assertThat(tools.searchMergeRequests("inexistant", null))
                .contains("Aucune").contains("inexistant");
    }

    @Test
    void limiteParDefautA20QuandNonPrecisee() {
        when(repository.search(eq("KDS"), anyInt())).thenReturn(List.of());

        tools.searchMergeRequests("KDS", null);

        verify(repository).search(eq("KDS"), eq(20));
    }

    @Test
    void plafonneLaLimiteA50() {
        when(repository.search(eq("KDS"), anyInt())).thenReturn(List.of());

        tools.searchMergeRequests("KDS", 999);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(repository).search(eq("KDS"), limit.capture());
        assertThat(limit.getValue()).isEqualTo(50);
    }
}
