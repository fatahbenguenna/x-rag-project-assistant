package com.domwil.xrag.application;

import com.domwil.xrag.domain.port.MaintenanceRepository;
import com.domwil.xrag.domain.port.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch nocturne (02:00, cible terminé ~02:45) :
 * health check → syncs incrémentales → réconciliation → VACUUM ANALYZE →
 * fiches projet → smoke test → notification. Règle absolue : jamais de
 * destruction d'index ; en cas d'échec du health check, on abandonne et
 * l'index de la veille reste servi.
 */
public class NightlyBatchService {

    private static final Logger log = LoggerFactory.getLogger(NightlyBatchService.class);


    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final SyncService syncService;
    private final MaintenanceRepository maintenance;
    private final ProjectSheetService projectSheets;
    private final SmokeTestService smokeTests;
    private final RagEvalService ragEval;
    private final GraphQualityService graphQuality;
    private final GraphEnrichmentService graphEnrichment;
    private final boolean llmEnrichmentEnabled;
    private final int llmMaxDocsPerNight;
    private final Notifier notifier;
    private final ChatModel chatModel;
    private final List<ChatOptions> chatWarmUps;

    /**
     * @param chatModel   modèle de chat à préchauffer à 07:30, ou null (provider distant)
     * @param chatWarmUps options de chaque modèle à préchauffer (mêmes model et num_ctx que
     *                    les requêtes réelles, sinon Ollama recharge le modèle à la première
     *                    question) ; vide = embedding seul
     */
    public NightlyBatchService(JdbcTemplate jdbc, EmbeddingModel embeddingModel,
                               SyncService syncService, MaintenanceRepository maintenance,
                               ProjectSheetService projectSheets, SmokeTestService smokeTests,
                               RagEvalService ragEval,
                               GraphQualityService graphQuality, GraphEnrichmentService graphEnrichment,
                               boolean llmEnrichmentEnabled, int llmMaxDocsPerNight, Notifier notifier,
                               ChatModel chatModel, List<ChatOptions> chatWarmUps) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.syncService = syncService;
        this.maintenance = maintenance;
        this.projectSheets = projectSheets;
        this.smokeTests = smokeTests;
        this.ragEval = ragEval;
        this.graphQuality = graphQuality;
        this.graphEnrichment = graphEnrichment;
        this.llmEnrichmentEnabled = llmEnrichmentEnabled;
        this.llmMaxDocsPerNight = llmMaxDocsPerNight;
        this.notifier = notifier;
        this.chatModel = chatModel;
        this.chatWarmUps = chatModel == null ? List.of() : List.copyOf(chatWarmUps);
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
            List<String> syncAnomalies = syncService.syncAll(false);

            int purged = maintenance.purgeOrphanNodes();
            log.info("Réconciliation : {} nœuds orphelins purgés", purged);
            maintenance.vacuumAnalyze();

            enrichGraphIfNeeded();

            projectSheets.refreshAll();
            String graphVerdict = graphQuality.evaluate().verdict();
            SmokeTestService.Report smoke = smokeTests.run();
            String evalReport = ragEval.hasCases() ? ragEval.evaluate().summary() : null;

            long minutes = Duration.between(start, Instant.now()).toMinutes();
            String stats = String.valueOf(maintenance.stats());
            log.info("Batch nocturne terminé en {} min — stats : {}", minutes, stats);
            String report = "Stats : " + stats + "\nÉval graphe : " + graphVerdict
                    + (evalReport == null ? "" : "\n" + evalReport)
                    + (syncAnomalies.isEmpty() ? "" : "\nSources en anomalie :\n" + String.join("\n", syncAnomalies))
                    + "\nSmoke test :\n" + smoke.summary();
            String title = "Batch nocturne terminé en " + minutes + " min";
            // Une source en erreur ou des documents sautés (PARTIEL : ils ne reviendront qu'à leur
            // prochaine modification), ou des réponses KO : une notification « terminé »
            // ordinaire passerait inaperçue.
            var problems = new ArrayList<String>();
            if (!syncAnomalies.isEmpty()) {
                problems.add(syncAnomalies.size() + " source(s) en anomalie");
            }
            if (!smoke.passed()) {
                problems.add("smoke test en échec");
            }
            if (problems.isEmpty()) {
                notifier.info(title, report);
            } else {
                notifier.alert(title + " — " + String.join(", ", problems), report);
            }
        } catch (Exception e) {
            log.error("ALERTE batch nocturne : échec en cours de batch (upsert only, "
                    + "l'index déjà servi reste intact)", e);
            notifier.alert("Batch nocturne en échec",
                    "Étape interrompue : " + e.getMessage() + " — l'index déjà servi reste intact.");
        }
    }

    /**
     * Enrichissement LLM du graphe (décision d'architecture n°10) : chaque nuit, les
     * documents SANS nœud TOPIC (nouveaux fichiers, docs jamais enrichis) sont traités,
     * plafonnés par nuit — puis GC des topics que plus aucun chunk ne référence.
     * L'ancien gate sur le ratio GLOBAL (< 50 %) ne se rouvrait jamais une fois franchi :
     * les nouveaux documents restaient définitivement sans topic (revue 2026-07, H6).
     */
    private void enrichGraphIfNeeded() {
        if (!llmEnrichmentEnabled) {
            return;
        }
        var report = graphEnrichment.enrichSources(java.util.List.of(), llmMaxDocsPerNight);
        int rewired = maintenance.dehubTopicEdges();
        int noisy = maintenance.purgeNoisyTopics();
        int purgedTopics = maintenance.purgeUnreferencedTopics();
        log.info("Enrichissement LLM du graphe (décision 10) : {} — {} arête(s) topic->document "
                + "recâblée(s), {} topic(s) bruyant(s) traité(s), {} non référencé(s) purgé(s)",
                report, rewired, noisy, purgedTopics);
    }

    /** Postgres + Ollama (embedding minimal) doivent répondre avant de toucher à l'index. */
    private void healthCheck() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        float[] probe = embeddingModel.embed("ping");
        if (probe == null || probe.length == 0) {
            throw new IllegalStateException("Le modèle d'embedding ne répond pas");
        }
    }

    /**
     * Warm-up 07:30 : recharge les modèles avant l'arrivée de l'équipe (OLLAMA_KEEP_ALIVE=24h).
     * L'embedding seul ne chargeait que bge-m3 : la première question du matin payait le
     * chargement du modèle de chat. Chaque modèle est préchauffé indépendamment.
     */
    public void warmUp() {
        try {
            embeddingModel.embed("warm-up");
            log.info("Warm-up du modèle d'embedding effectué");
        } catch (Exception e) {
            log.warn("Warm-up du modèle d'embedding en échec : {}", e.getMessage());
        }
        for (ChatOptions options : chatWarmUps) {
            try {
                chatModel.call(new Prompt("OK", options));
                log.info("Warm-up du modèle {} effectué", options.getModel());
            } catch (Exception e) {
                log.warn("Warm-up du modèle {} en échec : {}", options.getModel(), e.getMessage());
            }
        }
    }
}
