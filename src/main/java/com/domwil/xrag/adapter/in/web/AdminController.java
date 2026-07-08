package com.domwil.xrag.adapter.in.web;

import com.domwil.xrag.application.GraphQualityService;
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
    private final TaskExecutor taskExecutor;

    public AdminController(SyncService syncService, SmokeTestService smokeTests,
                           MaintenanceRepository maintenance, NightlyBatchService nightlyBatch,
                           GraphQualityService graphQuality,
                           @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.syncService = syncService;
        this.smokeTests = smokeTests;
        this.maintenance = maintenance;
        this.nightlyBatch = nightlyBatch;
        this.graphQuality = graphQuality;
        this.taskExecutor = taskExecutor;
    }

    /** Indexation à la demande. {@code full=true} = indexation initiale complète (bootstrap). */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(@RequestParam(defaultValue = "false") boolean full) {
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
