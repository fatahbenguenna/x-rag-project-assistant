package com.domwil.xrag.adapter.in.web;

import com.domwil.xrag.adapter.out.gitlab.GitLabConnector;
import com.domwil.xrag.application.SyncService;
import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Webhooks GitLab (push + merge_request) : upsert temps réel en journée,
 * pendant que Confluence/Jira restent au rythme du batch nocturne.
 * Configurer le webhook GitLab avec le secret GITLAB_WEBHOOK_TOKEN.
 */
@RestController
public class GitLabWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookController.class);

    private final SyncService syncService;
    private final TaskExecutor taskExecutor;
    private final String expectedToken;

    public GitLabWebhookController(SyncService syncService,
                                   @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                                   @Value("${GITLAB_WEBHOOK_TOKEN:}") String expectedToken) {
        this.syncService = syncService;
        this.taskExecutor = taskExecutor;
        this.expectedToken = expectedToken == null ? "" : expectedToken.strip();
        if (this.expectedToken.isEmpty()) {
            log.warn("GITLAB_WEBHOOK_TOKEN non configuré : POST /api/webhooks/gitlab accepte tout "
                    + "appel non authentifié qui atteint ce port — à réserver au développement "
                    + "(voir .env.example)");
        }
    }

    @PostMapping("/api/webhooks/gitlab")
    public ResponseEntity<Void> onEvent(
            @RequestHeader("X-Gitlab-Event") String event,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestBody JsonNode payload) {
        if (!expectedToken.isEmpty() && !tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        switch (event) {
            case "Push Hook" -> {
                log.info("Webhook push reçu ({}) : sync incrémentale du code",
                        payload.path("project").path("path_with_namespace").asText("?"));
                taskExecutor.execute(() -> syncService.syncSource(GitLabConnector.SOURCE, false));
            }
            case "Merge Request Hook" -> {
                log.info("Webhook merge_request reçu : sync des MRs");
                taskExecutor.execute(() -> syncService.syncMergeRequestsIncremental());
            }
            default -> log.debug("Webhook GitLab ignoré : {}", event);
        }
        return ResponseEntity.accepted().build();
    }

    /** Comparaison en temps constant — un secret ne se compare jamais avec equals(). */
    private boolean tokenMatches(String provided) {
        return provided != null && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
