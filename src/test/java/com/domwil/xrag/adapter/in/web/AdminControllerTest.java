package com.domwil.xrag.adapter.in.web;

import com.domwil.xrag.application.GraphEnrichmentService;
import com.domwil.xrag.application.GraphQualityService;
import com.domwil.xrag.application.IndexingProgressTracker;
import com.domwil.xrag.application.IndexingStatusService;
import com.domwil.xrag.application.IndexingStatusService.IndexingStatus;
import com.domwil.xrag.application.NightlyBatchService;
import com.domwil.xrag.application.SmokeTestService;
import com.domwil.xrag.application.SyncService;
import com.domwil.xrag.domain.port.IndexingStatusRepository.PersistedMetrics;
import com.domwil.xrag.domain.port.MaintenanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminControllerTest {

    private final IndexingStatusService indexingStatus = mock(IndexingStatusService.class);
    private final AdminController controller = new AdminController(
            mock(SyncService.class), mock(SmokeTestService.class), mock(MaintenanceRepository.class),
            mock(NightlyBatchService.class), mock(GraphQualityService.class),
            mock(GraphEnrichmentService.class), indexingStatus, mock(TaskExecutor.class));

    @Test
    void exposeLeStatutDIndexationPourLeDashboard() {
        IndexingProgressTracker tracker = new IndexingProgressTracker();
        tracker.startRun(false);
        tracker.startSource("gitlab-code");
        IndexingStatus expected = new IndexingStatus(
                new PersistedMetrics(15466L, Map.of("gitlab-code", 15271L), 62L, 40L, 208L, List.of()),
                tracker.snapshot());
        when(indexingStatus.current()).thenReturn(expected);

        IndexingStatus status = controller.indexingStatus();

        assertThat(status.metrics().totalChunks()).isEqualTo(15466L);
        assertThat(status.live().currentSource()).isEqualTo("gitlab-code");
    }
}
