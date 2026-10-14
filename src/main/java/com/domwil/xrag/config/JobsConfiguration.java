package com.domwil.xrag.config;

import com.domwil.xrag.application.AliasResolver;
import com.domwil.xrag.application.GraphQualityService;
import com.domwil.xrag.application.IndexingProgressTracker;
import com.domwil.xrag.application.IngestionService;
import com.domwil.xrag.application.NightlyBatchService;
import com.domwil.xrag.application.GraphEnrichmentService;
import com.domwil.xrag.application.EntityDetector;
import com.domwil.xrag.application.ProjectSheetService;
import com.domwil.xrag.application.RagEvalService;
import com.domwil.xrag.application.RagChatService;
import com.domwil.xrag.application.SmokeTestService;
import com.domwil.xrag.application.SyncService;
import com.domwil.xrag.adapter.out.notify.LoggingNotifier;
import com.domwil.xrag.adapter.out.notify.WebhookNotifier;
import com.domwil.xrag.domain.port.ChunkRepository;
import com.domwil.xrag.domain.port.ConnectorRegistry;
import com.domwil.xrag.domain.port.GraphQualityRepository;
import com.domwil.xrag.domain.port.GraphRepository;
import com.domwil.xrag.domain.port.GraphSearchRepository;
import com.domwil.xrag.domain.port.MaintenanceRepository;
import com.domwil.xrag.domain.port.MergeRequestRepository;
import com.domwil.xrag.domain.port.Notifier;
import com.domwil.xrag.domain.port.RelationExtractor;
import com.domwil.xrag.domain.port.SyncStateRepository;
import com.domwil.xrag.extraction.MergeRequestGraphMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.ArrayList;
import java.util.List;

/**
 * Jobs planifiés : batch nocturne (cron team-config, défaut 02:00) et warm-up
 * modèle à 07:30, à l'heure du fuseau du conteneur (XRAG_TZ, docker-compose.yml). En
 * journée, les webhooks GitLab font la mise à jour temps réel ; Confluence reste à J-1.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class JobsConfiguration implements SchedulingConfigurer {

    private static final String WARMUP_CRON = "0 30 7 * * *";

    private final TeamConfig config;
    private final ObjectProvider<NightlyBatchService> nightlyBatch;

    /** ObjectProvider : le service est défini plus bas dans cette même configuration. */
    public JobsConfiguration(TeamConfig config, ObjectProvider<NightlyBatchService> nightlyBatch) {
        this.config = config;
        this.nightlyBatch = nightlyBatch;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addCronTask(() -> nightlyBatch.getObject().run(), config.schedule().nightly());
        registrar.addCronTask(() -> nightlyBatch.getObject().warmUp(), WARMUP_CRON);
    }

    @Bean
    public IngestionService ingestionService(List<RelationExtractor> extractors, GraphRepository graph,
                                             ChunkRepository chunks, EmbeddingModel embeddingModel) {
        return new IngestionService(extractors, graph, chunks, embeddingModel);
    }

    @Bean
    public SyncService syncService(ConnectorRegistry connectors, IngestionService ingestion,
                                   SyncStateRepository syncState, MergeRequestRepository mergeRequests,
                                   MergeRequestGraphMapper mrMapper, GraphRepository graph,
                                   IndexingProgressTracker progressTracker,
                                   JdbcTemplate jdbc, EmbeddingModel embeddingModel) {
        return new SyncService(connectors, ingestion, syncState, mergeRequests, mrMapper, graph, progressTracker,
                () -> infrastructureUp(jdbc, embeddingModel));
    }

    /** Sonde de la sync : PostgreSQL et le modèle d'embedding répondent. */
    static boolean infrastructureUp(JdbcTemplate jdbc, EmbeddingModel embeddingModel) {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            float[] probe = embeddingModel.embed("ping");
            return probe != null && probe.length > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Bean
    public ProjectSheetService projectSheetService(AliasResolver aliases, GraphSearchRepository graphSearch,
                                                   ChunkRepository chunks, EmbeddingModel embeddingModel,
                                                   @Qualifier("synthesisChatClient") ChatClient chatClient,
                                                   IngestionService ingestion) {
        return new ProjectSheetService(aliases, graphSearch, chunks, embeddingModel, chatClient, ingestion);
    }

    @Bean
    public SmokeTestService smokeTestService(AliasResolver aliases, RagChatService ragChatService) {
        return new SmokeTestService(aliases, ragChatService);
    }

    @Bean
    public RagEvalService ragEvalService(EntityDetector entityDetector, GraphSearchRepository graphSearch,
                                         EmbeddingModel embeddingModel, ChunkRepository chunks,
                                         com.domwil.xrag.domain.port.ChunkReranker reranker,
                                         TeamConfig config) {
        var cases = config.eval().cases().stream()
                .map(c -> new RagEvalService.EvalCase(c.question(), c.expected()))
                .toList();
        return new RagEvalService(entityDetector, graphSearch, embeddingModel, chunks,
                reranker, config.retrieval().chunkLimit(), cases);
    }

    @Bean
    public GraphQualityService graphQualityService(GraphQualityRepository repository) {
        return new GraphQualityService(repository);
    }

    @Bean
    public Notifier notifier(@Value("${NOTIFY_WEBHOOK_URL:}") String webhookUrl) {
        return webhookUrl.isBlank() ? new LoggingNotifier()
                : new WebhookNotifier(RestClient.create(), webhookUrl);
    }

    @Bean
    public NightlyBatchService nightlyBatchService(JdbcTemplate jdbc, EmbeddingModel embeddingModel,
                                                   SyncService syncService, MaintenanceRepository maintenance,
                                                   ProjectSheetService projectSheets, SmokeTestService smokeTests,
                                                   RagEvalService ragEval, GraphQualityService graphQuality,
                                                   GraphEnrichmentService graphEnrichment,
                                                   TeamConfig config, Notifier notifier,
                                                   ObjectProvider<OllamaChatModel> ollama) {
        List<ChatOptions> chatWarmUps = chatWarmUpOptions(config.llm());
        return new NightlyBatchService(jdbc, embeddingModel, syncService, maintenance,
                projectSheets, smokeTests, ragEval, graphQuality, graphEnrichment,
                config.extractors().llm(), config.extractors().llmMaxDocsPerNight(), notifier,
                chatWarmUps.isEmpty() ? null : ollama.getIfAvailable(), chatWarmUps);
    }

    /**
     * Modèles de chat Ollama à préchauffer à 07:30 : le principal et, s'il est configuré, le
     * fallback (questions descriptives). Mêmes model et num_ctx que les requêtes réelles — un
     * num_ctx différent forcerait Ollama à recharger le modèle — et un seul token généré.
     * Aucun sur un provider distant.
     */
    static List<ChatOptions> chatWarmUpOptions(TeamConfig.Llm llm) {
        if (!"ollama".equals(llm.provider())) {
            return List.of();
        }
        var models = new ArrayList<String>();
        models.add(llm.model());
        if (llm.fallbackModel() != null && !llm.fallbackModel().isBlank()) {
            models.add(llm.fallbackModel());
        }
        return models.stream()
                .<ChatOptions>map(model -> OllamaChatOptions.builder()
                        .model(model)
                        .numCtx(llm.numCtx())
                        .numPredict(1)
                        .build())
                .toList();
    }
}
