package com.domwil.xrag.application;

import com.domwil.xrag.application.IndexingProgressTracker.ProgressSnapshot;
import com.domwil.xrag.application.IndexingProgressTracker.SourceProgress;
import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.MergeRequestMeta;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        org.mockito.Mockito.doCallRealMethod().when(connector).streamChangedSince(any(), any());
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
    void reconcilieLesMrsOuvertesQuandLeConnecteurEnConnaitLEnsemble() {
        MergeRequestConnector connector = mock(MergeRequestConnector.class);
        MergeRequestMeta open = mr("git-branch:fps:feat/a", "opened");
        when(connector.fetchUpdatedAfter(any())).thenReturn(List.of(open));
        when(connector.currentlyOpenIds()).thenReturn(Optional.of(Set.of("git-branch:fps:feat/a")));
        when(mrMapper.map(any())).thenReturn(ExtractionResult.empty());
        when(mergeRequests.deleteOpenedNotIn(any())).thenReturn(List.of("git-branch:fps:feat/b"));
        ConnectorRegistry registry = new ConnectorRegistry(List.of(), Optional.of(connector));
        var service = new SyncService(registry, ingestion, syncState, mergeRequests, mrMapper, graph, tracker);

        service.syncAll(false);

        verify(mergeRequests).upsert(List.of(open));
        verify(mergeRequests).deleteOpenedNotIn(Set.of("git-branch:fps:feat/a"));
        verify(graph).deleteNodes(List.of("mr:git-branch:fps:feat/b"));
    }

    @Test
    void sansInstantaneDesMrsOuvertesRienNestSupprime() {
        MergeRequestConnector connector = mock(MergeRequestConnector.class);
        when(connector.fetchUpdatedAfter(any())).thenReturn(List.of());
        when(connector.currentlyOpenIds()).thenReturn(Optional.empty()); // API GitLab, ou dépôt illisible
        ConnectorRegistry registry = new ConnectorRegistry(List.of(), Optional.of(connector));
        var service = new SyncService(registry, ingestion, syncState, mergeRequests, mrMapper, graph, tracker);

        service.syncAll(false);

        verify(mergeRequests, never()).deleteOpenedNotIn(any());
        verify(graph, never()).deleteNodes(any());
    }

    @Test
    void uneRecolteMrEnEchecNeReconcilieJamais() {
        MergeRequestConnector connector = mock(MergeRequestConnector.class);
        when(connector.fetchUpdatedAfter(any())).thenThrow(new IllegalStateException("dépôt illisible"));
        when(connector.currentlyOpenIds()).thenReturn(Optional.of(Set.of()));
        ConnectorRegistry registry = new ConnectorRegistry(List.of(), Optional.of(connector));
        var service = new SyncService(registry, ingestion, syncState, mergeRequests, mrMapper, graph, tracker);

        service.syncAll(false);

        verify(mergeRequests, never()).deleteOpenedNotIn(any());
        assertThat(tracker.snapshot().sources().getFirst().status()).contains("ERREUR");
    }

    @Test
    void leCurseurDesMrsEstBorneAMaintenant() {
        MergeRequestConnector connector = mock(MergeRequestConnector.class);
        when(connector.fetchUpdatedAfter(any())).thenReturn(List.of());
        when(mergeRequests.mostRecentUpdate()).thenReturn(Optional.of(Instant.parse("2999-01-01T00:00:00Z")));
        ConnectorRegistry registry = new ConnectorRegistry(List.of(), Optional.of(connector));
        var service = new SyncService(registry, ingestion, syncState, mergeRequests, mrMapper, graph, tracker);

        service.syncAll(false);

        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(connector).fetchUpdatedAfter(captor.capture());
        assertThat(captor.getValue()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void laSourceGitlabMrSeSynchroniseParSonNom() {
        MergeRequestConnector connector = mock(MergeRequestConnector.class);
        when(connector.fetchUpdatedAfter(any())).thenReturn(List.of());
        ConnectorRegistry registry = new ConnectorRegistry(List.of(), Optional.of(connector));
        var service = new SyncService(registry, ingestion, syncState, mergeRequests, mrMapper, graph, tracker);

        service.syncSource(SyncService.MR_SOURCE, true);

        verify(connector).fetchUpdatedAfter(null);
    }

    @Test
    void unConnecteurEnFluxEstConsommeSansListeIntermediaire() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("gitlab-code");
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<SourceDocument> sink = invocation.getArgument(1);
            sink.accept(document("gitlab-code", "a"));
            sink.accept(document("gitlab-code", "b"));
            return null;
        }).when(connector).streamChangedSince(any(), any());
        when(ingestion.ingest(any(), any(), anyBoolean())).thenReturn(true, false);

        syncServiceWith(connector).syncAll(false);

        verify(connector, never()).fetchChangedSince(any());
        SourceProgress progress = tracker.snapshot().sources().getFirst();
        assertThat(progress.indexed()).isEqualTo(1);
        assertThat(progress.total()).isEqualTo(2);
    }

    private static MergeRequestMeta mr(String id, String state) {
        return new MergeRequestMeta(id, "fps", 0, "titre", "", state, "bob", "feat/a", "main", null,
                List.of(), List.of(), Instant.now(), Instant.now(), null);
    }

    @Test
    void unEchecDeSourceEstReporteEtNInterrompntPasLeRun() {
        SourceConnector connector = mock(SourceConnector.class);
        when(connector.source()).thenReturn("gitlab-code");
        when(connector.fetchChangedSince(any())).thenThrow(new RuntimeException("GitLab HS"));
        org.mockito.Mockito.doCallRealMethod().when(connector).streamChangedSince(any(), any());

        syncServiceWith(connector).syncAll(false);

        ProgressSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.running()).isFalse();
        assertThat(snapshot.sources()).hasSize(1);
        assertThat(snapshot.sources().getFirst().status()).contains("ERREUR");
    }
}
