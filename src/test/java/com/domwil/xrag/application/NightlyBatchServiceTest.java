package com.domwil.xrag.application;

import com.domwil.xrag.domain.port.MaintenanceRepository;
import com.domwil.xrag.domain.port.Notifier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NightlyBatchServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmbeddingModel embeddings = mock(EmbeddingModel.class);
    private final SyncService sync = mock(SyncService.class);
    private final MaintenanceRepository maintenance = mock(MaintenanceRepository.class);
    private final ProjectSheetService sheets = mock(ProjectSheetService.class);
    private final SmokeTestService smoke = mock(SmokeTestService.class);
    private final RagEvalService ragEval = mock(RagEvalService.class);
    private final GraphQualityService graphQuality = mock(GraphQualityService.class);
    private final GraphEnrichmentService enrichment = mock(GraphEnrichmentService.class);
    private final Notifier notifier = mock(Notifier.class);

    private final ChatModel chatModel = mock(ChatModel.class);
    private final List<ChatOptions> warmUps = List.of(
            ChatOptions.builder().model("qwen2.5:7b-instruct").build(),
            ChatOptions.builder().model("qwen2.5:3b").build());

    private final NightlyBatchService batch = new NightlyBatchService(
            jdbc, embeddings, sync, maintenance, sheets, smoke, ragEval, graphQuality, enrichment, false, 150, notifier,
            chatModel, warmUps);

    @Test
    void warmUpPrechauffeLEmbeddingEtChaqueModeleDeChat() {
        batch.warmUp();

        verify(embeddings).embed("warm-up");
        verify(chatModel).call(argThat((Prompt p) -> "qwen2.5:7b-instruct".equals(p.getOptions().getModel())));
        verify(chatModel).call(argThat((Prompt p) -> "qwen2.5:3b".equals(p.getOptions().getModel())));
    }

    @Test
    void unWarmUpEnEchecNEmpechePasLesSuivants() {
        when(embeddings.embed(anyString())).thenThrow(new IllegalStateException("Ollama HS"));
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("modèle absent"));

        batch.warmUp();

        verify(chatModel, times(2)).call(any(Prompt.class));
    }

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
        when(smoke.run()).thenReturn(
                new SmokeTestService.Report("OK en 12 s — Explique-moi le projet fps-suite", true));
        when(graphQuality.evaluate()).thenReturn(new GraphQualityService.Report(
                new com.domwil.xrag.domain.model.GraphQualityMetrics(10, 20, 0, 100, 90, java.util.List.of()),
                java.util.List.of()));
        when(maintenance.stats()).thenReturn(java.util.Map.of("chunks", (Object) 42L));

        batch.run();

        verify(sync).syncAll(false);
        verify(maintenance).vacuumAnalyze();
        verify(notifier).info(contains("Batch nocturne terminé"), contains("Explique-moi le projet fps-suite"));
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
