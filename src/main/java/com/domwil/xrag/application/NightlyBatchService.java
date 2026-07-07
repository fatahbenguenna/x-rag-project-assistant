package com.domwil.xrag.application;

import com.domwil.xrag.domain.port.MaintenanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Batch nocturne (02:00, cible terminé ~02:45) :
 * health check → syncs incrémentales → réconciliation → VACUUM ANALYZE →
 * fiches projet → smoke test. Règle absolue : jamais de destruction d'index ;
 * en cas d'échec du health check, on abandonne et l'index de la veille reste servi.
 */
public class NightlyBatchService {

    private static final Logger log = LoggerFactory.getLogger(NightlyBatchService.class);

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final SyncService syncService;
    private final MaintenanceRepository maintenance;
    private final ProjectSheetService projectSheets;
    private final SmokeTestService smokeTests;

    public NightlyBatchService(JdbcTemplate jdbc, EmbeddingModel embeddingModel,
                               SyncService syncService, MaintenanceRepository maintenance,
                               ProjectSheetService projectSheets, SmokeTestService smokeTests) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.syncService = syncService;
        this.maintenance = maintenance;
        this.projectSheets = projectSheets;
        this.smokeTests = smokeTests;
    }

    public void run() {
        Instant start = Instant.now();
        log.info("Batch nocturne : démarrage");
        try {
            healthCheck();
        } catch (Exception e) {
            log.error("ALERTE batch nocturne : health check en échec, batch abandonné — "
                    + "l'index de la veille reste servi", e);
            return;
        }

        syncService.syncAll(false);

        int purged = maintenance.purgeOrphanNodes();
        log.info("Réconciliation : {} nœuds orphelins purgés", purged);
        maintenance.vacuumAnalyze();

        projectSheets.refreshAll();
        smokeTests.run();

        log.info("Batch nocturne terminé en {} min — stats : {}",
                Duration.between(start, Instant.now()).toMinutes(), maintenance.stats());
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
