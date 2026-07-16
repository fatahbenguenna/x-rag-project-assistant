package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.MergeRequestMeta;
import com.domwil.xrag.domain.port.ConnectorRegistry;
import com.domwil.xrag.domain.port.GraphRepository;
import com.domwil.xrag.domain.port.MergeRequestConnector;
import com.domwil.xrag.domain.port.MergeRequestRepository;
import com.domwil.xrag.domain.port.SourceConnector;
import com.domwil.xrag.domain.port.SyncStateRepository;
import com.domwil.xrag.extraction.MergeRequestGraphMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * Synchronisation des sources : incrémentale par défaut (curseur sync_state,
 * updated_after pour les MRs), complète pour le bootstrap initial. Utilisée
 * par le batch nocturne et les webhooks GitLab en journée.
 */
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    public static final String MR_SOURCE = "gitlab-mr";

    private final ConnectorRegistry connectors;
    private final IngestionService ingestion;
    private final SyncStateRepository syncState;
    private final MergeRequestRepository mergeRequests;
    private final MergeRequestGraphMapper mrMapper;
    private final GraphRepository graph;
    private final IndexingProgressTracker progress;

    public SyncService(ConnectorRegistry connectors, IngestionService ingestion,
                       SyncStateRepository syncState, MergeRequestRepository mergeRequests,
                       MergeRequestGraphMapper mrMapper, GraphRepository graph,
                       IndexingProgressTracker progress) {
        this.connectors = connectors;
        this.ingestion = ingestion;
        this.syncState = syncState;
        this.mergeRequests = mergeRequests;
        this.mrMapper = mrMapper;
        this.graph = graph;
        this.progress = progress;
    }

    public void syncAll(boolean full) {
        progress.startRun(full);
        try {
            for (SourceConnector connector : connectors.documentConnectors()) {
                syncSource(connector, full);
            }
            connectors.mergeRequestConnector().ifPresent(connector -> syncMergeRequests(connector, full));
        } finally {
            progress.finishRun();
        }
    }

    public void syncSource(String source, boolean full) {
        connectors.documentConnectors().stream()
                .filter(c -> c.source().equals(source))
                .forEach(c -> syncSource(c, full));
    }

    private void syncSource(SourceConnector connector, boolean full) {
        Instant startedAt = Instant.now();
        String source = connector.source();
        progress.startSource(source);
        Instant since = full ? null : syncState.lastSync(source).orElse(null);
        try {
            var documents = connector.fetchChangedSince(since);
            int indexed = 0;
            for (var doc : documents) {
                if (ingestion.ingest(doc)) {
                    indexed++;
                }
            }
            syncState.record(source, startedAt, "OK (%d/%d indexés)".formatted(indexed, documents.size()));
            progress.finishSource(source, "OK", indexed, documents.size());
            log.info("Sync {} : {}/{} documents (ré)indexés depuis {}",
                    source, indexed, documents.size(), since == null ? "toujours" : since);
        } catch (Exception e) {
            syncState.record(source, startedAt, "ERREUR " + e.getMessage());
            progress.finishSource(source, "ERREUR " + e.getMessage(), 0, 0);
            log.error("Sync {} en échec — l'index précédent reste servi", source, e);
        }
    }

    public void syncMergeRequestsIncremental() {
        connectors.mergeRequestConnector().ifPresent(connector -> syncMergeRequests(connector, false));
    }

    public void syncMergeRequests(MergeRequestConnector connector, boolean full) {
        Instant startedAt = Instant.now();
        progress.startSource(MR_SOURCE);
        Instant since = full ? null : mergeRequests.mostRecentUpdate().orElse(null);
        try {
            List<MergeRequestMeta> mrs = connector.fetchUpdatedAfter(since);
            mergeRequests.upsert(mrs);
            for (MergeRequestMeta mr : mrs) {
                graph.upsert(mrMapper.map(mr));
            }
            syncState.record(MR_SOURCE, startedAt, "OK (%d MRs)".formatted(mrs.size()));
            progress.finishSource(MR_SOURCE, "OK", mrs.size(), mrs.size());
            log.info("Sync MRs : {} mises à jour depuis {}", mrs.size(), since == null ? "toujours" : since);
        } catch (Exception e) {
            syncState.record(MR_SOURCE, startedAt, "ERREUR " + e.getMessage());
            progress.finishSource(MR_SOURCE, "ERREUR " + e.getMessage(), 0, 0);
            log.error("Sync MRs en échec", e);
        }
    }
}
