package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.model.Subgraph;
import com.domwil.xrag.domain.port.ChunkRepository;
import com.domwil.xrag.domain.port.GraphSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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
    private static final int CHUNK_LIMIT = 8;
    private static final int CHUNK_EXCERPT_CHARS = 1500;

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
    private final ModelRouter modelRouter;

    public RagChatService(ChatClient chatClient, EmbeddingModel embeddingModel,
                          EntityDetector entityDetector, GraphSearchRepository graphSearch,
                          ChunkRepository chunks, MergeRequestTools mergeRequestTools,
                          ModelRouter modelRouter) {
        this.chatClient = chatClient;
        this.embeddingModel = embeddingModel;
        this.entityDetector = entityDetector;
        this.graphSearch = graphSearch;
        this.chunks = chunks;
        this.mergeRequestTools = mergeRequestTools;
        this.modelRouter = modelRouter;
    }

    public Flux<String> answer(String question, String project) {
        // Le retrieval (graphe + embedding + recherche hybride) est bloquant : JDBC pour
        // Postgres, RestClient pour l'embedding Ollama. L'endpoint chat étant réactif
        // (WebFlux), on exécute tout le pipeline sur un scheduler élastique — sinon Reactor
        // rejette le block() sur le thread reactor-http-nio (erreur 500).
        return Flux.defer(() -> retrieveAndStream(question, project))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<String> retrieveAndStream(String question, String project) {
        Set<String> seeds = entityDetector.detectNodeIds(question);
        Subgraph subgraph = graphSearch.neighborhood(seeds, GRAPH_DEPTH);
        float[] embedding = embeddingModel.embed(question);
        List<ScoredChunk> retrieved =
                chunks.hybridSearch(embedding, question, subgraph.nodeIds(), project, CHUNK_LIMIT);
        log.debug("Retrieval : {} entités, {} nœuds de sous-graphe, {} chunks",
                seeds.size(), subgraph.nodes().size(), retrieved.size());

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
        spec = routed != null ? spec.options(routed) : spec.tools(mergeRequestTools);
        return spec.stream()
                .content();
    }

    private static String formatChunks(List<ScoredChunk> retrieved) {
        var sb = new StringBuilder();
        for (int i = 0; i < retrieved.size(); i++) {
            ScoredChunk chunk = retrieved.get(i);
            String excerpt = chunk.content().length() > CHUNK_EXCERPT_CHARS
                    ? chunk.content().substring(0, CHUNK_EXCERPT_CHARS) + "…"
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
