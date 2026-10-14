package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.MergeRequestMeta;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.ConnectorRegistry;
import com.domwil.xrag.domain.port.GraphRepository;
import com.domwil.xrag.domain.port.MergeRequestConnector;
import com.domwil.xrag.domain.port.MergeRequestRepository;
import com.domwil.xrag.domain.port.Notifier;
import com.domwil.xrag.domain.port.SourceConnector;
import com.domwil.xrag.domain.port.SyncStateRepository;
import com.domwil.xrag.extraction.MergeRequestGraphMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Synchronisation des sources : incrémentale par défaut (curseur sync_state,
 * updated_after pour les MRs), complète pour le bootstrap initial. Utilisée
 * par le batch nocturne et les webhooks GitLab en journée.
 */
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    public static final String MR_SOURCE = "gitlab-mr";
    private static final String PARTIAL = "PARTIEL";

    private final ConnectorRegistry connectors;
    private final IngestionService ingestion;
    private final SyncStateRepository syncState;
    private final MergeRequestRepository mergeRequests;
    private final MergeRequestGraphMapper mrMapper;
    private final GraphRepository graph;
    private final IndexingProgressTracker progress;
    private final BooleanSupplier infrastructureUp;
    private final Notifier notifier;

    /**
     * @param infrastructureUp sonde Ollama + PostgreSQL, interrogée quand un document échoue :
     *                         elle distingue un document illisible (sauté) d'une panne (sync
     *                         abandonnée, curseur conservé)
     * @param notifier         alerte des documents sautés lors d'une sync à la demande (webhook,
     *                         admin) : le batch nocturne suivant ne les verrait plus
     */
    public SyncService(ConnectorRegistry connectors, IngestionService ingestion,
                       SyncStateRepository syncState, MergeRequestRepository mergeRequests,
                       MergeRequestGraphMapper mrMapper, GraphRepository graph,
                       IndexingProgressTracker progress, BooleanSupplier infrastructureUp,
                       Notifier notifier) {
        this.connectors = connectors;
        this.ingestion = ingestion;
        this.syncState = syncState;
        this.mergeRequests = mergeRequests;
        this.mrMapper = mrMapper;
        this.graph = graph;
        this.progress = progress;
        this.infrastructureUp = infrastructureUp;
        this.notifier = notifier;
    }

    /** @return les statuts des sources qui ne sont pas OK (« source : statut »), vide si tout va bien */
    public List<String> syncAll(boolean full) {
        progress.startRun(full);
        var anomalies = new ArrayList<String>();
        try {
            for (SourceConnector connector : connectors.documentConnectors()) {
                collectAnomaly(anomalies, connector.source(), syncSource(connector, full));
            }
            connectors.mergeRequestConnector().ifPresent(connector ->
                    collectAnomaly(anomalies, MR_SOURCE, syncMergeRequests(connector, full)));
        } finally {
            progress.finishRun();
        }
        return anomalies;
    }

    private static void collectAnomaly(List<String> anomalies, String source, String status) {
        if (!status.startsWith("OK")) {
            anomalies.add(source + " : " + status);
        }
    }

    /**
     * Sync à la demande d'une source (webhook GitLab, admin). Des documents sautés y sont
     * alertés tout de suite : le curseur a avancé, le batch nocturne ne les reverra pas.
     */
    public void syncSource(String source, boolean full) {
        connectors.documentConnectors().stream()
                .filter(c -> c.source().equals(source))
                .forEach(c -> {
                    String status = syncSource(c, full);
                    if (status.startsWith(PARTIAL)) {
                        notifier.alert("Sync " + source + " : documents ignorés", status
                                + "\nIls ne seront ré-indexés qu'à leur prochaine modification "
                                + "(ou via POST /api/admin/sync?source=" + source + "&full=true).");
                    }
                });
    }

    private String syncSource(SourceConnector connector, boolean full) {
        Instant startedAt = Instant.now();
        String source = connector.source();
        progress.startSource(source);
        Instant since = full ? null : syncState.lastSync(source).orElse(null);
        try {
            var documents = connector.fetchChangedSince(since);
            int indexed = 0;
            var failed = new ArrayList<String>();
            DocumentFailure lastFailure = null;
            for (var doc : documents) {
                try {
                    if (ingestWithRetry(doc, full)) {
                        indexed++;
                    }
                } catch (DocumentFailure e) {
                    // Document illisible (entrée refusée par le modèle, contrainte SQL…) avec une
                    // infrastructure saine : on le saute. Le curseur figé sur lui le ferait
                    // échouer à chaque sync et bloquerait toute la source.
                    failed.add(doc.path());
                    lastFailure = e;
                    log.warn("Sync {} : document {} ignoré ({})", source, doc.path(), e.getMessage());
                }
            }
            // Aucun document réellement tenté n'a abouti (les inchangés et les vides ne comptent
            // pas) : panne non détectée plutôt que documents illisibles, curseur conservé.
            if (indexed == 0 && lastFailure != null) {
                throw new IllegalStateException("aucun des %d documents tentés n'a pu être indexé, dernier échec : %s"
                        .formatted(failed.size(), lastFailure.getMessage()), lastFailure);
            }
            String status = failed.isEmpty()
                    ? "OK (%d/%d indexés)".formatted(indexed, documents.size())
                    : PARTIAL + " (%d/%d indexés, %d en échec : %s)".formatted(indexed, documents.size(),
                            failed.size(), String.join(", ", failed.subList(0, Math.min(5, failed.size()))));
            syncState.record(source, startedAt, status);
            progress.finishSource(source, status, indexed, documents.size());
            log.info("Sync {} : {}/{} documents (ré)indexés depuis {}{}",
                    source, indexed, documents.size(), since == null ? "toujours" : since,
                    failed.isEmpty() ? "" : " — " + failed.size() + " en échec");
            return status;
        } catch (Exception e) {
            // Curseur inchangé : la prochaine sync reprend depuis la dernière réussie.
            String status = "ERREUR " + e.getMessage();
            syncState.recordFailure(source, status);
            progress.finishSource(source, status, 0, 0);
            log.error("Sync {} en échec — l'index précédent reste servi", source, e);
            return status;
        }
    }

    /**
     * Un échec d'ingestion est une panne (l'exception remonte, la sync est abandonnée, curseur
     * conservé, les documents restants seront repris) quand son type le dit — connexion,
     * disque plein, timeout, que la sonde SELECT 1 + embedding ne voit pas toujours — ou quand
     * la sonde échoue. Sinon : nouvel essai (un redémarrage d'Ollama peut avoir coupé le
     * premier), puis {@link DocumentFailure} si le document échoue encore.
     *
     * @param full ré-indexation forcée (récupère les changements d'extraction, ex. commentaires,
     *             même si la version source n'a pas bougé)
     */
    private boolean ingestWithRetry(SourceDocument doc, boolean full) {
        try {
            return ingestion.ingest(doc, Set.of(), full);
        } catch (RuntimeException first) {
            if (isInfrastructureFailure(first) || !infrastructureUp.getAsBoolean()) {
                throw first;
            }
            try {
                return ingestion.ingest(doc, Set.of(), full);
            } catch (RuntimeException second) {
                if (isInfrastructureFailure(second)) {
                    throw second;
                }
                throw new DocumentFailure(second);
            }
        }
    }

    /**
     * Pannes reconnues à leur type : PostgreSQL injoignable, disque plein (SQLState 53100),
     * timeout ou verrou ({@link DataAccessResourceFailureException},
     * {@link TransientDataAccessException}), Ollama injoignable ({@link ResourceAccessException}).
     */
    static boolean isInfrastructureFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof DataAccessResourceFailureException
                    || t instanceof TransientDataAccessException
                    || t instanceof ResourceAccessException) {
                return true;
            }
        }
        return false;
    }

    /** Échec propre à un document, infrastructure saine. */
    private static final class DocumentFailure extends RuntimeException {
        DocumentFailure(RuntimeException cause) {
            super(cause.getMessage(), cause);
        }
    }

    public void syncMergeRequestsIncremental() {
        connectors.mergeRequestConnector().ifPresent(connector -> syncMergeRequests(connector, false));
    }

    public String syncMergeRequests(MergeRequestConnector connector, boolean full) {
        Instant startedAt = Instant.now();
        progress.startSource(MR_SOURCE);
        Instant since = full ? null : mergeRequests.mostRecentUpdate().orElse(null);
        try {
            List<MergeRequestMeta> mrs = connector.fetchUpdatedAfter(since);
            mergeRequests.upsert(mrs);
            for (MergeRequestMeta mr : mrs) {
                graph.upsert(mrMapper.map(mr));
            }
            String status = "OK (%d MRs)".formatted(mrs.size());
            syncState.record(MR_SOURCE, startedAt, status);
            progress.finishSource(MR_SOURCE, "OK", mrs.size(), mrs.size());
            log.info("Sync MRs : {} mises à jour depuis {}", mrs.size(), since == null ? "toujours" : since);
            return status;
        } catch (Exception e) {
            // Le curseur des MRs est max(updated_at) de merge_requests : sync_state ne sert
            // ici qu'à l'affichage, qui garde la date de la dernière sync réussie.
            String status = "ERREUR " + e.getMessage();
            syncState.recordFailure(MR_SOURCE, status);
            progress.finishSource(MR_SOURCE, status, 0, 0);
            log.error("Sync MRs en échec", e);
            return status;
        }
    }
}
