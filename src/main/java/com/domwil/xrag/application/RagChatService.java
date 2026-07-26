package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.MergeRequestMeta;
import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.model.Subgraph;
import com.domwil.xrag.domain.port.ChunkReranker;
import com.domwil.xrag.domain.port.ChunkRepository;
import com.domwil.xrag.domain.port.MergeRequestRepository;
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

    /**
     * Clé Jira citée dans la question (FPSSUITE-2, PASS-42…). Insensible à la casse
     * (« fpssuite-2 » à l'oral s'écrit souvent en minuscules) — un faux positif type
     * « top-5 » coûte au pire une recherche d'id vide, sans effet sur le prompt.
     */
    static final java.util.regex.Pattern ISSUE_KEY = java.util.regex.Pattern.compile(
            "\\b([A-Z][A-Z0-9]+-\\d+)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
    /** Numéro de MR cité dans la question (!153, « MR 153 », « merge request 153 »). */
    static final java.util.regex.Pattern MR_IID = java.util.regex.Pattern.compile(
            "(?:!|\\b(?:mr|merge[ -]request)\\s*[!#]?)(\\d{1,6})\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

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
            {exactNote}
            Question : {question}
            """;

    /**
     * Consigne d'ancrage injectée UNIQUEMENT quand la question cite un identifiant
     * (clé Jira, numéro de MR) : sans marquage, le modèle 7B pondère tous les documents
     * à égalité et la référence est noyée par le bruit lexical (échec mesuré : « décris
     * la MR !153 » répondu depuis d'autres MRs alors que la description exacte était
     * en tête des sources).
     */
    private static final String EXACT_NOTE = """
            Les documents marqués « RÉFÉRENCE EXACTE » sont ceux que la question désigne \
            par leur identifiant : fonde ta réponse d'abord sur eux ; les autres documents \
            ne sont que du contexte complémentaire.""";

    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;
    private final EntityDetector entityDetector;
    private final GraphSearchRepository graphSearch;
    private final ChunkRepository chunks;
    private final MergeRequestRepository mergeRequests;
    private final MergeRequestTools mergeRequestTools;
    private final KnowledgeBaseTools knowledgeBaseTools;
    private final ModelRouter modelRouter;
    private final ChunkReranker reranker;
    private final int chunkLimit;
    private final int chunkExcerptChars;

    public RagChatService(ChatClient chatClient, EmbeddingModel embeddingModel,
                          EntityDetector entityDetector, GraphSearchRepository graphSearch,
                          ChunkRepository chunks, MergeRequestRepository mergeRequests,
                          MergeRequestTools mergeRequestTools,
                          KnowledgeBaseTools knowledgeBaseTools, ModelRouter modelRouter,
                          ChunkReranker reranker, int chunkLimit, int chunkExcerptChars) {
        this.chatClient = chatClient;
        this.embeddingModel = embeddingModel;
        this.entityDetector = entityDetector;
        this.graphSearch = graphSearch;
        this.chunks = chunks;
        this.mergeRequests = mergeRequests;
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
        List<ScoredChunk> reranked = reranker.rerank(question, candidates, chunkLimit);
        // Références EXACTES citées dans la question (clé Jira, numéro de MR) : leurs
        // documents complets sont pré-injectés en tête — DÉTERMINISTE. Le retrieval
        // sémantique ne privilégie pas un identifiant, et l'appel de tool par un petit
        // modèle est probabiliste (échec mesuré : « commentaires de FPSSUITE-2 » répondu
        // « aucun commentaire » sans consulter l'issue, chunk pourtant indexé).
        Retrieved retrieved = withExactReferences(question, reranked);
        log.debug("Retrieval : {} entités, {} nœuds de sous-graphe, {} candidats -> {} chunks "
                        + "(dont {} références exactes)", seeds.size(), subgraph.nodes().size(),
                candidates.size(), retrieved.chunks().size(), retrieved.exactCount());

        var spec = chatClient.prompt()
                .user(user -> user.text(USER_TEMPLATE)
                        .param("graph", subgraph.edges().isEmpty() ? "(aucune relation trouvée)"
                                : GraphTextSerializer.serialize(subgraph))
                        .param("documents", retrieved.chunks().isEmpty() ? "(aucun document trouvé)"
                                : formatChunks(retrieved.chunks(), retrieved.exactCount()))
                        .param("exactNote", retrieved.exactCount() > 0 ? EXACT_NOTE : "")
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

    /** Sources du prompt : les {@code exactCount} DERNIERS chunks sont des références exactes. */
    record Retrieved(List<ScoredChunk> chunks, int exactCount) {}

    /**
     * Contexte complémentaire maximal quand une référence exacte est présente : le bruit
     * du retrieval dilue la référence (échec mesuré avec 8 chunks : « décris la MR !153 »
     * répondu depuis des chunks sans rapport malgré la référence dans le prompt).
     */
    private static final int EXACT_CONTEXT_LIMIT = 4;

    /**
     * Documents désignés par identifiant dans la question, placés en FIN de sources,
     * adjacents à la question (dédupliqués) : les petits modèles pondèrent la fin du
     * prompt bien plus que le début — en tête, la référence était ignorée au profit
     * du bruit de similarité.
     */
    Retrieved withExactReferences(String question, List<ScoredChunk> retrieved) {
        var exact = new java.util.ArrayList<ScoredChunk>();
        var seen = new java.util.HashSet<String>();
        var issueMatcher = ISSUE_KEY.matcher(question);
        while (issueMatcher.find()) {
            String key = issueMatcher.group(1).toUpperCase(java.util.Locale.ROOT);
            for (ScoredChunk chunk : chunks.documentChunks("jira", key)) {
                if (seen.add(chunk.id())) {
                    exact.add(chunk);
                }
            }
        }
        var mrMatcher = MR_IID.matcher(question);
        while (mrMatcher.find()) {
            mergeRequests.findByIid(Long.parseLong(mrMatcher.group(1)))
                    .map(RagChatService::mergeRequestChunk)
                    .filter(chunk -> seen.add(chunk.id()))
                    .ifPresent(exact::add);
        }
        if (exact.isEmpty()) {
            return new Retrieved(retrieved, 0);
        }
        var combined = new java.util.ArrayList<ScoredChunk>(EXACT_CONTEXT_LIMIT + exact.size());
        for (ScoredChunk chunk : retrieved) {
            if (combined.size() >= EXACT_CONTEXT_LIMIT) {
                break;
            }
            if (!seen.contains(chunk.id())) {
                combined.add(chunk);
            }
        }
        combined.addAll(exact);
        return new Retrieved(List.copyOf(combined), exact.size());
    }

    /** La MR comme source citée : titre + description complète, format aligné sur les chunks. */
    private static ScoredChunk mergeRequestChunk(MergeRequestMeta mr) {
        String content = "!" + mr.iid() + " [" + mr.state() + "] " + mr.title() + "\n"
                + (mr.description() == null || mr.description().isBlank()
                        ? "(aucune description)" : mr.description());
        return new ScoredChunk("mr-exact-" + mr.iid(), "gitlab-mr", mr.project(),
                "!" + mr.iid(), mr.title(), content, mr.webUrl(), 1.0);
    }

    String formatChunks(List<ScoredChunk> retrieved, int exactCount) {
        var sb = new StringBuilder();
        for (int i = 0; i < retrieved.size(); i++) {
            ScoredChunk chunk = retrieved.get(i);
            String excerpt = chunk.content().length() > chunkExcerptChars
                    ? chunk.content().substring(0, chunkExcerptChars) + "…"
                    : chunk.content();
            sb.append("[").append(i + 1).append("] ");
            if (i >= retrieved.size() - exactCount) {
                sb.append("RÉFÉRENCE EXACTE — ");
            }
            sb.append(chunk.citation());
            if (chunk.url() != null) {
                sb.append(" (").append(chunk.url()).append(")");
            }
            sb.append("\n").append(excerpt).append("\n\n");
        }
        return sb.toString().strip();
    }
}
