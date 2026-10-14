package com.domwil.xrag.application;

import com.domwil.xrag.application.IndexingProgressTracker.ProgressSnapshot;
import com.domwil.xrag.application.IndexingProgressTracker.SourceProgress;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.ConnectorRegistry;
import com.domwil.xrag.domain.port.GraphRepository;
import com.domwil.xrag.domain.port.MergeRequestConnector;
import com.domwil.xrag.domain.port.MergeRequestRepository;
import com.domwil.xrag.domain.port.Notifier;
import com.domwil.xrag.domain.port.SourceConnector;
import com.domwil.xrag.domain.port.SyncStateRepository;
import com.domwil.xrag.extraction.MergeRequestGraphMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.client.ResourceAccessException;

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
    private final Notifier notifier = mock(Notifier.class);
    private boolean infrastructureUp = true;

    private SyncService syncServiceWith(SourceConnector connector) {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(connector), Optional.empty());
        return new SyncService(registry, ingestion, syncState, mergeRequests, mrMapper, graph, tracker,
                () -> infrastructureUp, notifier);
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
        assertThat(progress.status()).isEqualTo("OK (2/2 indexés)");
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
        // Le tracker (prioritaire au dashboard) reçoit le détail, pas seulement « PARTIEL ».
        assertThat(tracker.snapshot().sources().getFirst().status()).contains("db/export-utf16.sql");
    }

    @Test
    void desDocumentsIllisiblesContigusNeBloquentPasLaSource() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("gitlab-code");
        SourceDocument sain = document("gitlab-code", "src/Service.java");
        when(connector.fetchChangedSince(any())).thenReturn(List.of(
                document("gitlab-code", "db/export1.sql"), document("gitlab-code", "db/export2.sql"),
                document("gitlab-code", "db/export3.sql"), sain));
        when(ingestion.ingest(any(), any(), anyBoolean())).thenThrow(new IllegalStateException("0x00"));
        org.mockito.Mockito.doReturn(true).when(ingestion).ingest(eq(sain), any(), anyBoolean());

        syncServiceWith(connector).syncAll(false);

        // Infrastructure saine : les échecs sont propres aux documents, la source avance.
        verify(ingestion).ingest(eq(sain), any(), anyBoolean());
        verify(syncState).record(eq("gitlab-code"), any(Instant.class), startsWith("PARTIEL (1/4 indexés, 3 en échec"));
        verify(syncState, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void unePanneEnCoursDeLotGardeLeCurseurEtArreteLaSync() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("gitlab-code");
        SourceDocument a = document("gitlab-code", "A.java");
        SourceDocument b = document("gitlab-code", "B.java");
        SourceDocument c = document("gitlab-code", "C.java");
        when(connector.fetchChangedSince(any())).thenReturn(List.of(a, b, c));
        when(ingestion.ingest(eq(a), any(), anyBoolean())).thenReturn(true);
        when(ingestion.ingest(eq(b), any(), anyBoolean())).thenThrow(new IllegalStateException("Connection refused"));
        infrastructureUp = false;

        syncServiceWith(connector).syncAll(false);

        // Panne d'Ollama : B et C seront repris à la prochaine sync, rien n'est sauté.
        verify(ingestion, never()).ingest(eq(c), any(), anyBoolean());
        verify(syncState, never()).record(eq("gitlab-code"), any(), anyString());
        verify(syncState).recordFailure(eq("gitlab-code"), contains("Connection refused"));
    }

    @Test
    void unEchecPassagerEstRattrapeParUnNouvelEssai() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("confluence");
        SourceDocument page = document("confluence", "page-1");
        when(connector.fetchChangedSince(any())).thenReturn(List.of(page));
        when(ingestion.ingest(eq(page), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("Ollama redémarre"))
                .thenReturn(true);

        syncServiceWith(connector).syncAll(false);

        verify(ingestion, times(2)).ingest(eq(page), any(), anyBoolean());
        verify(syncState).record(eq("confluence"), any(Instant.class), eq("OK (1/1 indexés)"));
    }

    @Test
    void unDisquePleinEnCoursDeLotEstUnePanneMemeSiLaSondeRepond() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("confluence");
        SourceDocument p1 = document("confluence", "p1");
        SourceDocument p2 = document("confluence", "p2");
        SourceDocument p3 = document("confluence", "p3");
        when(connector.fetchChangedSince(any())).thenReturn(List.of(p1, p2, p3));
        when(ingestion.ingest(eq(p1), any(), anyBoolean())).thenReturn(true);
        // SELECT 1 et l'embedding répondent encore, mais aucun INSERT ne passe (SQLState 53100).
        when(ingestion.ingest(eq(p2), any(), anyBoolean())).thenThrow(
                new DataAccessResourceFailureException("could not extend file: No space left on device"));

        syncServiceWith(connector).syncAll(false);

        verify(ingestion, never()).ingest(eq(p3), any(), anyBoolean());
        verify(syncState, never()).record(eq("confluence"), any(), anyString());
        verify(syncState).recordFailure(eq("confluence"), contains("No space left on device"));
    }

    @Test
    void ollamaInjoignableEstUnePanneSansNouvelEssai() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("jira");
        SourceDocument issue = document("jira", "PASS-1");
        when(connector.fetchChangedSince(any())).thenReturn(List.of(issue, document("jira", "PASS-2")));
        when(ingestion.ingest(eq(issue), any(), anyBoolean()))
                .thenThrow(new ResourceAccessException("I/O error on POST http://ollama:11434/api/embed"));

        syncServiceWith(connector).syncAll(false);

        verify(ingestion, times(1)).ingest(any(), any(), anyBoolean());
        verify(syncState).recordFailure(eq("jira"), contains("ollama:11434"));
    }

    @Test
    void desDocumentsInchangesNeMasquentPasUnePanneNonDetectee() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("confluence");
        SourceDocument inchangee = document("confluence", "index");
        SourceDocument modifiee = document("confluence", "p1");
        when(connector.fetchChangedSince(any())).thenReturn(List.of(inchangee, modifiee));
        when(ingestion.ingest(eq(inchangee), any(), anyBoolean())).thenReturn(false);
        when(ingestion.ingest(eq(modifiee), any(), anyBoolean())).thenThrow(new IllegalStateException("échec inconnu"));

        syncServiceWith(connector).syncAll(false);

        // Aucun document tenté n'a abouti : le curseur reste, la page sera retentée.
        verify(syncState, never()).record(eq("confluence"), any(), anyString());
        verify(syncState).recordFailure(eq("confluence"), contains("dernier échec : échec inconnu"));
    }

    @Test
    void uneSyncALaDemandeAvecDesDocumentsIgnoresAlerte() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("gitlab-code");
        SourceDocument illisible = document("gitlab-code", "db/x.sql");
        when(connector.fetchChangedSince(any())).thenReturn(List.of(document("gitlab-code", "A.java"), illisible));
        when(ingestion.ingest(any(), any(), anyBoolean())).thenReturn(true);
        when(ingestion.ingest(eq(illisible), any(), anyBoolean())).thenThrow(new IllegalStateException("0x00"));

        // Chemin du webhook GitLab : le batch nocturne suivant ne reverra pas db/x.sql.
        syncServiceWith(connector).syncSource("gitlab-code", false);

        verify(notifier).alert(contains("documents ignorés"), contains("db/x.sql"));
    }

    @Test
    void syncAllRenvoieLesSourcesEnAnomalie() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("jira");
        when(connector.fetchChangedSince(any())).thenThrow(new RuntimeException("401 Unauthorized"));

        List<String> anomalies = syncServiceWith(connector).syncAll(false);

        assertThat(anomalies).containsExactly("jira : ERREUR 401 Unauthorized");
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

        new SyncService(registry, ingestion, syncState, mergeRequests, mrMapper, graph, tracker, () -> true, notifier)
                .syncMergeRequestsIncremental();

        verify(syncState, never()).record(eq(SyncService.MR_SOURCE), any(), anyString());
        verify(syncState).recordFailure(eq(SyncService.MR_SOURCE), contains("GitLab HS"));
    }
}
