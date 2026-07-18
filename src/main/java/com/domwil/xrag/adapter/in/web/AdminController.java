package com.domwil.xrag.adapter.in.web;

import com.domwil.xrag.application.GraphEnrichmentService;
import com.domwil.xrag.application.GraphQualityService;
import com.domwil.xrag.application.IndexingStatusService;
import com.domwil.xrag.application.IndexingStatusService.IndexingStatus;
import com.domwil.xrag.application.NightlyBatchService;
import com.domwil.xrag.application.SmokeTestService;
import com.domwil.xrag.application.SyncService;
import com.domwil.xrag.domain.port.MaintenanceRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Endpoints d'administration utilisés par bootstrap.sh et pour le suivi de l'index. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SyncService syncService;
    private final SmokeTestService smokeTests;
    private final MaintenanceRepository maintenance;
    private final NightlyBatchService nightlyBatch;
    private final GraphQualityService graphQuality;
    private final GraphEnrichmentService graphEnrichment;
    private final IndexingStatusService indexingStatus;
    private final TaskExecutor taskExecutor;

    public AdminController(SyncService syncService, SmokeTestService smokeTests,
                           MaintenanceRepository maintenance, NightlyBatchService nightlyBatch,
                           GraphQualityService graphQuality, GraphEnrichmentService graphEnrichment,
                           IndexingStatusService indexingStatus,
                           @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.syncService = syncService;
        this.smokeTests = smokeTests;
        this.maintenance = maintenance;
        this.nightlyBatch = nightlyBatch;
        this.graphQuality = graphQuality;
        this.graphEnrichment = graphEnrichment;
        this.indexingStatus = indexingStatus;
        this.taskExecutor = taskExecutor;
    }

    /**
     * Indexation à la demande. {@code full=true} = ré-indexation forcée (même version inchangée,
     * ex. après ajout des commentaires). {@code source} = cible une seule source (confluence,
     * gitlab-code, jira) ; absent = toutes.
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(@RequestParam(defaultValue = "false") boolean full,
                                                    @RequestParam(required = false) String source) {
        if (source != null && !source.isBlank()) {
            taskExecutor.execute(() -> syncService.syncSource(source, full));
            return ResponseEntity.accepted().body(Map.of("started", true, "full", full, "source", source));
        }
        taskExecutor.execute(() -> syncService.syncAll(full));
        return ResponseEntity.accepted().body(Map.of("started", true, "full", full));
    }

    /** Déclenche le batch nocturne complet (syncs + réconciliation + fiches + smoke test). */
    @PostMapping("/nightly")
    public ResponseEntity<Map<String, Object>> nightly() {
        taskExecutor.execute(nightlyBatch::run);
        return ResponseEntity.accepted().body(Map.of("started", true));
    }

    @PostMapping("/smoke-test")
    public Map<String, Object> smokeTest() {
        return Map.of("report", smokeTests.run());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return maintenance.stats();
    }

    /**
     * Statut détaillé pour le dashboard de monitoring : chunks par source, tailles
     * du graphe, MRs, dernière sync par source, indexation en cours (source, durée)
     * et problèmes récents. Alimente {@code /dashboard.html} par polling.
     */
    @GetMapping("/indexing-status")
    public IndexingStatus indexingStatus() {
        return indexingStatus.current();
    }

    /**
     * Enrichissement LLM du graphe à la demande (décision 10) : crée des nœuds TOPIC pour les
     * documents non rattachés et y relie leurs chunks. Asynchrone sur le taskExecutor (appels
     * LLM bloquants — jamais sur l'event-loop réactif) ; le bilan est tracé dans les logs.
     * Complète le déclenchement automatique du batch nocturne.
     */
    @PostMapping("/enrich")
    public ResponseEntity<Map<String, Object>> enrich(@RequestParam(defaultValue = "50") int max,
                                                      @RequestParam(required = false) String sources) {
        int capped = Math.max(1, Math.min(max, 500));
        if (sources != null && !sources.isBlank()) {
            var targets = java.util.Arrays.stream(sources.split(",")).map(String::trim)
                    .filter(s -> !s.isBlank()).toList();
            taskExecutor.execute(() -> graphEnrichment.enrichSources(targets, capped));
            return ResponseEntity.accepted().body(Map.of("started", true, "max", capped, "sources", targets));
        }
        taskExecutor.execute(() -> graphEnrichment.enrich(capped));
        return ResponseEntity.accepted().body(Map.of("started", true, "max", capped, "sources", "unattached"));
    }

    /** Couverture du graphe + trous détectés (décision 10 : extraction LLM seulement si trous). */
    @GetMapping("/graph-quality")
    public Map<String, Object> graphQuality() {
        var report = graphQuality.evaluate();
        return Map.of(
                "metrics", report.metrics(),
                "gaps", report.gaps(),
                "verdict", report.verdict());
    }
}
