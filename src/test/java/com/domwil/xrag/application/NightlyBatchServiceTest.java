package com.domwil.xrag.application;

import com.domwil.xrag.domain.port.MaintenanceRepository;
import com.domwil.xrag.domain.port.Notifier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NightlyBatchServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmbeddingModel embeddings = mock(EmbeddingModel.class);
    private final SyncService sync = mock(SyncService.class);
    private final MaintenanceRepository maintenance = mock(MaintenanceRepository.class);
    private final ProjectSheetService sheets = mock(ProjectSheetService.class);
    private final SmokeTestService smoke = mock(SmokeTestService.class);
    private final GraphQualityService graphQuality = mock(GraphQualityService.class);
    private final Notifier notifier = mock(Notifier.class);

    private final NightlyBatchService batch = new NightlyBatchService(
            jdbc, embeddings, sync, maintenance, sheets, smoke, graphQuality, notifier);

    @Test
    void healthCheckEnEchecAlerteEtAbandonne() {
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenThrow(new IllegalStateException("connexion refusée"));

        batch.run();

        verify(notifier).alert(eq("Batch nocturne abandonné"), contains("l'index de la veille reste servi"));
        verify(sync, never()).syncAll(false);
    }

    @Test
    void batchCompletNotifieLeRapportAvecSmokeTest() {
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(embeddings.embed(anyString())).thenReturn(new float[]{1f});
        when(smoke.run()).thenReturn("OK en 12 s — Explique-moi le projet elog");
        when(graphQuality.evaluate()).thenReturn(new GraphQualityService.Report(
                new com.domwil.xrag.domain.model.GraphQualityMetrics(10, 20, 0, 100, 90, java.util.List.of()),
                java.util.List.of()));
        when(maintenance.stats()).thenReturn(java.util.Map.of("chunks", (Object) 42L));

        batch.run();

        verify(sync).syncAll(false);
        verify(maintenance).vacuumAnalyze();
        verify(notifier).info(contains("Batch nocturne terminé"), contains("Explique-moi le projet elog"));
        verify(notifier, never()).alert(anyString(), anyString());
    }

    @Test
    void echecEnCoursDeBatchAlerteSansCasserLIndex() {
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(embeddings.embed(anyString())).thenReturn(new float[]{1f});
        org.mockito.Mockito.doThrow(new RuntimeException("GitLab HS")).when(sync).syncAll(false);

        batch.run();

        verify(notifier).alert(eq("Batch nocturne en échec"), contains("GitLab HS"));
        verify(notifier, never()).info(anyString(), any());
    }
}
