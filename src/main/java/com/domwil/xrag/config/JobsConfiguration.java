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
import org.springframework.ai.embedding.EmbeddingModel;
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

import java.util.List;

/**
 * Jobs planifiés : batch nocturne (cron team-config, défaut 02:00) et warm-up
 * modèle à 07:30. En journée, les webhooks GitLab font la mise à jour temps
 * réel ; Confluence reste à J-1.
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
                                   IndexingProgressTracker progressTracker) {
        return new SyncService(connectors, ingestion, syncState, mergeRequests, mrMapper, graph, progressTracker);
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
                                         TeamConfig config) {
        var cases = config.eval().cases().stream()
                .map(c -> new RagEvalService.EvalCase(c.question(), c.expected()))
                .toList();
        return new RagEvalService(entityDetector, graphSearch, embeddingModel, chunks, cases);
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
                                                   TeamConfig config, Notifier notifier) {
        return new NightlyBatchService(jdbc, embeddingModel, syncService, maintenance,
                projectSheets, smokeTests, ragEval, graphQuality, graphEnrichment,
                config.extractors().llm(), notifier);
    }
}
