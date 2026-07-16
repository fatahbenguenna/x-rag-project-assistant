package com.domwil.xrag.application;

import com.domwil.xrag.application.IndexingProgressTracker.ProgressSnapshot;
import com.domwil.xrag.application.IndexingProgressTracker.SourceProgress;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.ConnectorRegistry;
import com.domwil.xrag.domain.port.GraphRepository;
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
import static org.mockito.Mockito.mock;
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
        when(ingestion.ingest(any())).thenReturn(true);

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
}
