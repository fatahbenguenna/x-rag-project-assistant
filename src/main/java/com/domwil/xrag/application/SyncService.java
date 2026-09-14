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

    /** {@code source} = identifiant d'un connecteur documentaire, ou {@link #MR_SOURCE} pour les MRs. */
    public void syncSource(String source, boolean full) {
        if (MR_SOURCE.equals(source)) {
            connectors.mergeRequestConnector().ifPresent(connector -> syncMergeRequests(connector, full));
            return;
        }
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
            int[] counters = new int[2]; // [0] reçus, [1] (ré)indexés
            // En flux : un connecteur à gros volume (dépôts git) n'accumule pas sa récolte en RAM.
            connector.streamChangedSince(since, doc -> {
                counters[0]++;
                // full=true force la ré-indexation (récupère les changements d'extraction, ex.
                // commentaires, même si la version source n'a pas bougé).
                if (ingestion.ingest(doc, java.util.Set.of(), full)) {
                    counters[1]++;
                }
            });
            int indexed = counters[1];
            int total = counters[0];
            syncState.record(source, startedAt, "OK (%d/%d indexés)".formatted(indexed, total));
            progress.finishSource(source, "OK", indexed, total);
            log.info("Sync {} : {}/{} documents (ré)indexés depuis {}",
                    source, indexed, total, since == null ? "toujours" : since);
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
        // Curseur borné à « maintenant » : une date de commit fantaisiste (horloge en avance,
        // GIT_COMMITTER_DATE) ne doit pas figer la sync incrémentale dans le futur.
        Instant since = full ? null : mergeRequests.mostRecentUpdate()
                .map(t -> t.isAfter(startedAt) ? startedAt : t).orElse(null);
        try {
            List<MergeRequestMeta> mrs = connector.fetchUpdatedAfter(since);
            mergeRequests.upsert(mrs);
            for (MergeRequestMeta mr : mrs) {
                graph.upsert(mrMapper.map(mr));
            }
            connector.currentlyOpenIds().ifPresent(this::reconcileOpen);
            syncState.record(MR_SOURCE, startedAt, "OK (%d MRs)".formatted(mrs.size()));
            progress.finishSource(MR_SOURCE, "OK", mrs.size(), mrs.size());
            log.info("Sync MRs : {} mises à jour depuis {}", mrs.size(), since == null ? "toujours" : since);
        } catch (Exception e) {
            syncState.record(MR_SOURCE, startedAt, "ERREUR " + e.getMessage());
            progress.finishSource(MR_SOURCE, "ERREUR " + e.getMessage(), 0, 0);
            log.error("Sync MRs en échec", e);
        }
    }

    /**
     * MRs ouvertes : quand le connecteur en connaît l'ensemble complet (branches non fusionnées
     * d'un dépôt git local), celles qui ont disparu (fusionnées, supprimées) sont retirées de la
     * table et du graphe — sinon « quelle MR ouverte est la plus vieille ? » répondrait faux.
     * Jamais appelé sur une récolte en échec (fail-closed).
     */
    private void reconcileOpen(java.util.Set<String> openIds) {
        List<String> removed = mergeRequests.deleteOpenedNotIn(openIds);
        if (!removed.isEmpty()) {
            graph.deleteNodes(removed.stream().map(id -> "mr:" + id).toList());
            log.info("Sync MRs : {} MR(s) ouverte(s) disparue(s) retirée(s) : {}", removed.size(), removed);
        }
    }
}
