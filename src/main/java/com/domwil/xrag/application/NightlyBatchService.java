package com.domwil.xrag.application;

import com.domwil.xrag.domain.port.MaintenanceRepository;
import com.domwil.xrag.domain.port.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Batch nocturne (02:00, cible terminé ~02:45) :
 * health check → syncs incrémentales → réconciliation → VACUUM ANALYZE →
 * fiches projet → smoke test → notification. Règle absolue : jamais de
 * destruction d'index ; en cas d'échec du health check, on abandonne et
 * l'index de la veille reste servi.
 */
public class NightlyBatchService {

    private static final Logger log = LoggerFactory.getLogger(NightlyBatchService.class);

    /** Plafond de documents enrichis par nuit (budget de temps du batch) ; le reste suit les nuits suivantes. */
    private static final int MAX_ENRICHMENT_DOCS = 150;

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final SyncService syncService;
    private final MaintenanceRepository maintenance;
    private final ProjectSheetService projectSheets;
    private final SmokeTestService smokeTests;
    private final GraphQualityService graphQuality;
    private final GraphEnrichmentService graphEnrichment;
    private final boolean llmEnrichmentEnabled;
    private final Notifier notifier;

    public NightlyBatchService(JdbcTemplate jdbc, EmbeddingModel embeddingModel,
                               SyncService syncService, MaintenanceRepository maintenance,
                               ProjectSheetService projectSheets, SmokeTestService smokeTests,
                               GraphQualityService graphQuality, GraphEnrichmentService graphEnrichment,
                               boolean llmEnrichmentEnabled, Notifier notifier) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.syncService = syncService;
        this.maintenance = maintenance;
        this.projectSheets = projectSheets;
        this.smokeTests = smokeTests;
        this.graphQuality = graphQuality;
        this.graphEnrichment = graphEnrichment;
        this.llmEnrichmentEnabled = llmEnrichmentEnabled;
        this.notifier = notifier;
    }

    public void run() {
        Instant start = Instant.now();
        log.info("Batch nocturne : démarrage");
        try {
            healthCheck();
        } catch (Exception e) {
            log.error("ALERTE batch nocturne : health check en échec, batch abandonné — "
                    + "l'index de la veille reste servi", e);
            notifier.alert("Batch nocturne abandonné",
                    "Health check en échec (" + e.getMessage() + ") — l'index de la veille reste servi.");
            return;
        }

        try {
            syncService.syncAll(false);

            int purged = maintenance.purgeOrphanNodes();
            log.info("Réconciliation : {} nœuds orphelins purgés", purged);
            maintenance.vacuumAnalyze();

            enrichGraphIfNeeded();

            projectSheets.refreshAll();
            String graphVerdict = graphQuality.evaluate().verdict();
            String smokeReport = smokeTests.run();

            long minutes = Duration.between(start, Instant.now()).toMinutes();
            String stats = String.valueOf(maintenance.stats());
            log.info("Batch nocturne terminé en {} min — stats : {}", minutes, stats);
            notifier.info("Batch nocturne terminé en " + minutes + " min",
                    "Stats : " + stats + "\nÉval graphe : " + graphVerdict
                            + "\nSmoke test :\n" + smokeReport);
        } catch (Exception e) {
            log.error("ALERTE batch nocturne : échec en cours de batch (upsert only, "
                    + "l'index déjà servi reste intact)", e);
            notifier.alert("Batch nocturne en échec",
                    "Étape interrompue : " + e.getMessage() + " — l'index déjà servi reste intact.");
        }
    }

    /**
     * Enrichissement LLM du graphe (décision d'architecture n°10) : uniquement si activé
     * (config) ET si l'éval montre le trou de rattachement — sinon l'extraction déterministe
     * suffit et on n'ajoute pas de coût LLM.
     */
    private void enrichGraphIfNeeded() {
        if (!llmEnrichmentEnabled) {
            return;
        }
        var metrics = graphQuality.evaluate().metrics();
        if (metrics.linkedChunkRatio() >= GraphQualityService.MIN_LINKED_CHUNK_RATIO) {
            return;
        }
        log.info("Enrichissement LLM du graphe (décision 10, {}% de chunks rattachés) : {}",
                Math.round(metrics.linkedChunkRatio() * 100),
                graphEnrichment.enrich(MAX_ENRICHMENT_DOCS));
    }

    /** Postgres + Ollama (embedding minimal) doivent répondre avant de toucher à l'index. */
    private void healthCheck() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        float[] probe = embeddingModel.embed("ping");
        if (probe == null || probe.length == 0) {
            throw new IllegalStateException("Le modèle d'embedding ne répond pas");
        }
    }

    /** Warm-up 07:30 : recharge le modèle avant l'arrivée de l'équipe (OLLAMA_KEEP_ALIVE=24h). */
    public void warmUp() {
        try {
            embeddingModel.embed("warm-up");
            log.info("Warm-up modèle effectué");
        } catch (Exception e) {
            log.warn("Warm-up en échec : {}", e.getMessage());
        }
    }
}
