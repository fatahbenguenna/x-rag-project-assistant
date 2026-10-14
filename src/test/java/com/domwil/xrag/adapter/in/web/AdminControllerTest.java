package com.domwil.xrag.adapter.in.web;

import com.domwil.xrag.application.GraphEnrichmentService;
import com.domwil.xrag.application.GraphQualityService;
import com.domwil.xrag.application.RagEvalService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminControllerTest {

    private final IndexingStatusService indexingStatus = mock(IndexingStatusService.class);
    private final SmokeTestService smokeTests = mock(SmokeTestService.class);
    private final AdminController controller = new AdminController(
            mock(SyncService.class), smokeTests, mock(MaintenanceRepository.class),
            mock(NightlyBatchService.class), mock(GraphQualityService.class),
            mock(GraphEnrichmentService.class), indexingStatus, mock(RagEvalService.class),
            mock(TaskExecutor.class));

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

    @Test
    void smokeTestSExecuteHorsDeLEventLoop() {
        // Sur l'event loop WebFlux (thread NonBlocking), le block() du service lève une exception.
        AtomicReference<String> thread = new AtomicReference<>();
        when(smokeTests.run()).thenAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            return new SmokeTestService.Report("OK en 3 s — question", true);
        });

        ResponseEntity<Map<String, Object>> response = controller.smokeTest().block(Duration.ofSeconds(5));

        assertThat(thread.get()).startsWith("boundedElastic");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("passed", true);
    }

    @Test
    void smokeTestEnEchecRepond503() {
        when(smokeTests.run()).thenReturn(new SmokeTestService.Report("KO (réponse vide) — question", false));

        ResponseEntity<Map<String, Object>> response = controller.smokeTest().block(Duration.ofSeconds(5));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .containsEntry("passed", false)
                .containsEntry("report", "KO (réponse vide) — question");
    }
}
