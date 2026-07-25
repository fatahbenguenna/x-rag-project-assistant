package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.model.Subgraph;
import com.domwil.xrag.domain.port.ChunkReranker;
import com.domwil.xrag.domain.port.ChunkRepository;
import com.domwil.xrag.domain.port.GraphSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.embedding.EmbeddingModel;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Set;

/**
 * Pipeline GraphRAG hybride : (1) détection d'entités via alias, (2) expansion
 * graphe profondeur 2, (3) sous-graphe sérialisé injecté au prompt,
 * (4) recherche vectorielle + full-text boostée par les nœuds du sous-graphe.
 * Réponse streamée (premier token ~2-5 s en CPU).
 */
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    private static final int GRAPH_DEPTH = 2;

    private static final String USER_TEMPLATE = """
            Contexte issu du graphe de connaissances de l'équipe (relations extraites du code, \
            de Confluence, des MRs et de Jira) :
            <graphe>
            {graph}
            </graphe>

            Extraits de documentation, code et tickets (sources numérotées) :
            <documents>
            {documents}
            </documents>

            Question : {question}
            """;

    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;
    private final EntityDetector entityDetector;
    private final GraphSearchRepository graphSearch;
    private final ChunkRepository chunks;
    private final MergeRequestTools mergeRequestTools;
    private final KnowledgeBaseTools knowledgeBaseTools;
    private final ModelRouter modelRouter;
    private final ChunkReranker reranker;
    private final int chunkLimit;
    private final int chunkExcerptChars;

    public RagChatService(ChatClient chatClient, EmbeddingModel embeddingModel,
                          EntityDetector entityDetector, GraphSearchRepository graphSearch,
                          ChunkRepository chunks, MergeRequestTools mergeRequestTools,
                          KnowledgeBaseTools knowledgeBaseTools, ModelRouter modelRouter,
                          ChunkReranker reranker, int chunkLimit, int chunkExcerptChars) {
        this.chatClient = chatClient;
        this.embeddingModel = embeddingModel;
        this.entityDetector = entityDetector;
        this.graphSearch = graphSearch;
        this.chunks = chunks;
        this.mergeRequestTools = mergeRequestTools;
        this.knowledgeBaseTools = knowledgeBaseTools;
        this.modelRouter = modelRouter;
        this.reranker = reranker;
        this.chunkLimit = chunkLimit;
        this.chunkExcerptChars = chunkExcerptChars;
    }

    public Flux<String> answer(String question, String project) {
        return streamWithUsage(question, project)
                .map(RagChatService::contentOf)
                .filter(token -> !token.isEmpty());
    }

    /**
     * Comme {@link #answer}, mais expose le {@link ChatResponse} complet — dont les
     * métadonnées d'usage (tokens prompt/réponse), consommées par la façade OpenAI-compatible.
     */
    public Flux<ChatResponse> streamWithUsage(String question, String project) {
        // Le retrieval (graphe + embedding + recherche hybride) est bloquant : JDBC pour
        // Postgres, RestClient pour l'embedding Ollama. L'endpoint chat étant réactif
        // (WebFlux), on exécute tout le pipeline sur un scheduler élastique — sinon Reactor
        // rejette le block() sur le thread reactor-http-nio (erreur 500).
        return Flux.defer(() -> retrieveAndStream(question, project))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Texte d'un fragment de réponse ({@code ""} s'il n'en porte pas, ex. le fragment final). */
    public static String contentOf(ChatResponse response) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private Flux<ChatResponse> retrieveAndStream(String question, String project) {
        Set<String> seeds = entityDetector.detectNodeIds(question);
        Subgraph subgraph = graphSearch.neighborhood(seeds, GRAPH_DEPTH);
        float[] embedding = embeddingModel.embed(question);
        // Reranker actif : vivier élargi (~40) reclassé par le cross-encoder, et le boost
        // graphe passe de 0.3 à 0.1 — il ne sert plus qu'au rappel du vivier, le
        // cross-encoder jugeant la pertinence mieux que l'heuristique (revue 2026-07, M1).
        int poolSize = reranker.candidatePoolSize(chunkLimit);
        double graphBoost = poolSize > chunkLimit ? 0.1 : 0.3;
        List<ScoredChunk> candidates =
                chunks.hybridSearch(embedding, question, subgraph.nodeIds(), project, poolSize, graphBoost);
        List<ScoredChunk> retrieved = reranker.rerank(question, candidates, chunkLimit);
        log.debug("Retrieval : {} entités, {} nœuds de sous-graphe, {} candidats -> {} chunks",
                seeds.size(), subgraph.nodes().size(), candidates.size(), retrieved.size());

        var spec = chatClient.prompt()
                .user(user -> user.text(USER_TEMPLATE)
                        .param("graph", subgraph.isEmpty() ? "(aucune relation trouvée)"
                                : GraphTextSerializer.serialize(subgraph))
                        .param("documents", retrieved.isEmpty() ? "(aucun document trouvé)"
                                : formatChunks(retrieved))
                        .param("question", question));
        var routed = modelRouter.route(question);
        // Descriptif → modèle fallback léger, sans tools (les petits modèles sont
        // peu fiables en function calling et le descriptif n'en a pas besoin).
        // Modèle principal → tools MRs + recherche plein-texte de la base de connaissances.
        spec = routed != null ? spec.options(routed.mutate())
                : spec.tools(mergeRequestTools, knowledgeBaseTools);
        return spec.stream()
                .chatResponse();
    }

    private String formatChunks(List<ScoredChunk> retrieved) {
        var sb = new StringBuilder();
        for (int i = 0; i < retrieved.size(); i++) {
            ScoredChunk chunk = retrieved.get(i);
            String excerpt = chunk.content().length() > chunkExcerptChars
                    ? chunk.content().substring(0, chunkExcerptChars) + "…"
                    : chunk.content();
            sb.append("[").append(i + 1).append("] ").append(chunk.citation());
            if (chunk.url() != null) {
                sb.append(" (").append(chunk.url()).append(")");
            }
            sb.append("\n").append(excerpt).append("\n\n");
        }
        return sb.toString().strip();
    }
}
