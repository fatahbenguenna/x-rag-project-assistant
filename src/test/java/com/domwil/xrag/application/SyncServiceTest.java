package com.domwil.xrag.application;

import com.domwil.xrag.application.IndexingProgressTracker.ProgressSnapshot;
import com.domwil.xrag.application.IndexingProgressTracker.SourceProgress;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.ConnectorRegistry;
import com.domwil.xrag.domain.port.GraphRepository;
import com.domwil.xrag.domain.port.MergeRequestConnector;
import com.domwil.xrag.domain.port.MergeRequestRepository;
import com.domwil.xrag.domain.port.SourceConnector;
import com.domwil.xrag.domain.port.SyncStateRepository;
import com.domwil.xrag.extraction.MergeRequestGraphMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncServiceTest {

    private final IngestionService ingestion = mock(IngestionService.class);
    private final SyncStateRepository syncState = mock(SyncStateRepository.class);
    private final MergeRequestRepository mergeRequests = mock(MergeRequestRepository.class);
    private final MergeRequestGraphMapper mrMapper = mock(MergeRequestGraphMapper.class);
    private final GraphRepository graph = mock(GraphRepository.class);
    private final IndexingProgressTracker tracker = new IndexingProgressTracker();

    private SyncService syncServiceWith(SourceConnector connector) {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(connector), Optional.empty());
        return new SyncService(registry, ingestion, syncState, mergeRequests, mrMapper, graph, tracker);
    }

    private static SourceDocument document(String source, String path) {
        return new SourceDocument(source, null, path, "titre", "contenu", null, "v1", Instant.now(), null);
    }

    @Test
    void suitLeCycleDeVieDeLIndexationDansLeTracker() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("confluence");
        when(connector.fetchChangedSince(any())).thenReturn(
                List.of(document("confluence", "page-1"), document("confluence", "page-2")));
        when(ingestion.ingest(any(), any(), anyBoolean())).thenReturn(true);

        syncServiceWith(connector).syncAll(true);

        ProgressSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.running()).isFalse();
        assertThat(snapshot.lastRunDurationSeconds()).isNotNull();
        assertThat(snapshot.sources()).hasSize(1);
        SourceProgress progress = snapshot.sources().getFirst();
        assertThat(progress.source()).isEqualTo("confluence");
        assertThat(progress.status()).isEqualTo("OK");
        assertThat(progress.indexed()).isEqualTo(2);
        assertThat(progress.total()).isEqualTo(2);
        assertThat(progress.finishedAt()).isNotNull();
    }

    @Test
    void unEchecDeSourceEstReporteEtNInterrompntPasLeRun() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("gitlab-code");
        when(connector.fetchChangedSince(any())).thenThrow(new RuntimeException("GitLab HS"));

        syncServiceWith(connector).syncAll(false);

        ProgressSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.running()).isFalse();
        assertThat(snapshot.sources()).hasSize(1);
        assertThat(snapshot.sources().getFirst().status()).contains("ERREUR");
    }

    @Test
    void unEchecDeSourceNAvancePasLeCurseurDeSync() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("jira");
        when(connector.fetchChangedSince(any())).thenThrow(new RuntimeException("401 Unauthorized"));

        syncServiceWith(connector).syncAll(false);

        // Avancer last_sync sur un échec ferait sauter la fenêtre manquée à la sync suivante.
        verify(syncState, never()).record(eq("jira"), any(), anyString());
        verify(syncState).recordFailure(eq("jira"), contains("401 Unauthorized"));
    }

    @Test
    void uneSyncReussieAvanceLeCurseur() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("jira");
        when(connector.fetchChangedSince(any())).thenReturn(List.of(document("jira", "PASS-1")));
        when(ingestion.ingest(any(), any(), anyBoolean())).thenReturn(true);

        syncServiceWith(connector).syncAll(false);

        verify(syncState).record(eq("jira"), any(Instant.class), startsWith("OK"));
        verify(syncState, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void unDocumentEnEchecEstSauteSansBloquerLaSource() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("gitlab-code");
        SourceDocument poison = document("gitlab-code", "db/export-utf16.sql");
        when(connector.fetchChangedSince(any())).thenReturn(List.of(
                document("gitlab-code", "src/A.java"), poison, document("gitlab-code", "src/B.java")));
        when(ingestion.ingest(any(), any(), anyBoolean())).thenReturn(true);
        when(ingestion.ingest(eq(poison), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("invalid byte sequence for encoding UTF8: 0x00"));

        syncServiceWith(connector).syncAll(false);

        // Curseur figé sur ce document, il échouerait à chaque sync et bloquerait toute la source.
        verify(syncState).record(eq("gitlab-code"), any(Instant.class),
                eq("PARTIEL (2/3 indexés, 1 en échec : db/export-utf16.sql)"));
        verify(syncState, never()).recordFailure(anyString(), anyString());
        assertThat(tracker.snapshot().sources().getFirst().indexed()).isEqualTo(2);
    }

    @Test
    void desEchecsEnRafaleSignalentUnePanneEtGardentLeCurseur() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("confluence");
        when(connector.fetchChangedSince(any())).thenReturn(List.of(
                document("confluence", "p1"), document("confluence", "p2"), document("confluence", "p3"),
                document("confluence", "p4")));
        when(ingestion.ingest(any(), any(), anyBoolean())).thenThrow(new IllegalStateException("Ollama HS"));

        syncServiceWith(connector).syncAll(false);

        verify(ingestion, times(SyncService.MAX_CONSECUTIVE_FAILURES)).ingest(any(), any(), anyBoolean());
        verify(syncState, never()).record(eq("confluence"), any(), anyString());
        verify(syncState).recordFailure(eq("confluence"), contains("Ollama HS"));
    }

    @Test
    void unLotEntierementEnEchecGardeLeCurseur() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("jira");
        when(connector.fetchChangedSince(any())).thenReturn(List.of(document("jira", "PASS-1")));
        when(ingestion.ingest(any(), any(), anyBoolean())).thenThrow(new IllegalStateException("Ollama HS"));

        syncServiceWith(connector).syncAll(false);

        verify(syncState, never()).record(eq("jira"), any(), anyString());
        verify(syncState).recordFailure(eq("jira"), contains("aucun des 1 documents"));
    }

    @Test
    void unEchecDeSyncDesMrsGardeLaDateDeDerniereSyncReussie() {
        // Le curseur des MRs est max(updated_at) de merge_requests ; sync_state ne sert qu'à
        // l'affichage (dashboard), qui doit montrer la dernière sync réussie et le statut d'erreur.
        MergeRequestConnector connector = mock(MergeRequestConnector.class);
        when(connector.fetchUpdatedAfter(any())).thenThrow(new RuntimeException("GitLab HS"));
        ConnectorRegistry registry = new ConnectorRegistry(List.of(), Optional.of(connector));

        new SyncService(registry, ingestion, syncState, mergeRequests, mrMapper, graph, tracker)
                .syncMergeRequestsIncremental();

        verify(syncState, never()).record(eq(SyncService.MR_SOURCE), any(), anyString());
        verify(syncState).recordFailure(eq(SyncService.MR_SOURCE), contains("GitLab HS"));
    }
}
