package com.domwil.xrag.application;

import com.domwil.xrag.application.IndexingStatusService.IndexingStatus;
import com.domwil.xrag.domain.port.IndexingStatusRepository;
import com.domwil.xrag.domain.port.IndexingStatusRepository.PersistedMetrics;
import com.domwil.xrag.domain.port.IndexingStatusRepository.SourceSyncState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexingStatusServiceTest {

    private final IndexingStatusRepository repository = mock(IndexingStatusRepository.class);
    private final IndexingProgressTracker tracker = new IndexingProgressTracker();
    private final IndexingStatusService service = new IndexingStatusService(repository, tracker);

    @Test
    void agregeLesMetriquesPersisteesEtLEtatVivant() {
        PersistedMetrics metrics = new PersistedMetrics(
                15466L,
                Map.of("confluence", 118L, "gitlab-code", 15271L, "jira", 76L, "project-sheet", 1L),
                62L, 40L, 208L,
                List.of(new SourceSyncState("confluence", Instant.parse("2026-07-16T02:00:00Z"), "OK (2/2 indexés)")));
        when(repository.metrics()).thenReturn(metrics);
        tracker.startRun(false);
        tracker.startSource("gitlab-code");

        IndexingStatus status = service.current();

        assertThat(status.metrics().totalChunks()).isEqualTo(15466L);
        assertThat(status.metrics().chunksBySource()).containsEntry("gitlab-code", 15271L);
        assertThat(status.metrics().sources()).hasSize(1);
        assertThat(status.live().running()).isTrue();
        assertThat(status.live().currentSource()).isEqualTo("gitlab-code");
    }

    @Test
    void exposeLEtatAuReposQuandAucuneIndexationNEstEnCours() {
        when(repository.metrics()).thenReturn(new PersistedMetrics(
                0L, Map.of(), 0L, 0L, 0L, List.of()));

        IndexingStatus status = service.current();

        assertThat(status.live().running()).isFalse();
        assertThat(status.live().currentSource()).isNull();
        assertThat(status.metrics().totalChunks()).isZero();
    }
}
