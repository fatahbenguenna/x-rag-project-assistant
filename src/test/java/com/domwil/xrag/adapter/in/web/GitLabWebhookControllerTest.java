package com.domwil.xrag.adapter.in.web;

import com.domwil.xrag.adapter.out.gitlab.GitLabConnector;
import com.domwil.xrag.application.SyncService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GitLabWebhookControllerTest {

    private static final JsonNode PAYLOAD = JsonNodeFactory.instance.objectNode();
    private static final TaskExecutor DIRECT = Runnable::run;

    private final SyncService sync = mock(SyncService.class);

    @Test
    void refuseUnSecretErroneOuAbsent() {
        var controller = new GitLabWebhookController(sync, DIRECT, "s3cret");

        assertThat(controller.onEvent("Push Hook", "mauvais", PAYLOAD).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.onEvent("Push Hook", null, PAYLOAD).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(sync, never()).syncSource(anyString(), anyBoolean());
    }

    @Test
    void accepteLeBonSecretEtLanceLaSyncDuCode() {
        var controller = new GitLabWebhookController(sync, DIRECT, " s3cret\n");

        assertThat(controller.onEvent("Push Hook", "s3cret", PAYLOAD).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        verify(sync).syncSource(GitLabConnector.SOURCE, false);
    }

    @Test
    void sansSecretConfigureLeWebhookResteOuvert() {
        // Convention de ADMIN_TOKEN : vide = accès libre (développement) + avertissement au démarrage.
        var controller = new GitLabWebhookController(sync, DIRECT, "");

        assertThat(controller.onEvent("Merge Request Hook", null, PAYLOAD).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        verify(sync).syncMergeRequestsIncremental();
    }
}
