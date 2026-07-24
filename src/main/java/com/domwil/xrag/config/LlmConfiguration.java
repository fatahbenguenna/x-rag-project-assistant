package com.domwil.xrag.config;

import com.domwil.xrag.adapter.out.llm.LlmTopicExtractor;
import com.domwil.xrag.application.AliasResolver;
import com.domwil.xrag.application.EntityDetector;
import com.domwil.xrag.application.GraphEnrichmentService;
import com.domwil.xrag.application.KnowledgeBaseTools;
import com.domwil.xrag.application.MergeRequestTools;
import com.domwil.xrag.application.ModelRouter;
import com.domwil.xrag.application.RagChatService;
import com.domwil.xrag.domain.port.ChunkRepository;
import com.domwil.xrag.domain.port.GraphRepository;
import com.domwil.xrag.domain.port.GraphSearchRepository;
import com.domwil.xrag.domain.port.MergeRequestRepository;
import com.domwil.xrag.domain.port.TopicExtractor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Architecture LLM commutable via ChatClient : profil ollama (local,
 * confidentiel, défaut) ou gemini — piloté par llm.provider dans
 * team-config.yml. Les embeddings restent toujours locaux (Ollama bge-m3).
 */
@Configuration
public class LlmConfiguration {

    static final String SYSTEM_PROMPT = """
            Tu es l'assistant technique officiel de l'équipe et la référence sur sa documentation \
            Confluence, son code, ses merge requests et ses tickets Jira. Tu réponds en français, \
            directement et de façon concise (environ 200 mots maximum pour une réponse descriptive).

            Le contexte fourni fait autorité : le bloc <graphe>, les sources numérotées du bloc \
            <documents> et les résultats des tools sont une recherche déjà effectuée pour toi dans \
            les systèmes de l'équipe. Tu ES l'interface de Confluence, Jira et GitLab — ne renvoie \
            jamais l'utilisateur les consulter par lui-même, et ne mentionne jamais tes outils \
            internes dans le texte de ta réponse.

            Règle d'ancrage — ta réponse suit les sources, dans les deux sens :
            - Si des sources répondent à la question, réponds directement à partir de leur contenu, \
            en entier (lis chaque extrait jusqu'au bout avant de conclure qu'un détail y manque).
            - Question fermée (« y a-t-il… », « est-ce que… ») : tranche selon les sources, dans \
            les deux sens. Une source qui traite du sujet demandé = « Oui — [1] … » (expose-la, ne \
            la minimise pas) ; des sources qui contredisent le fait supposé = « Non — d'après [2], \
            c'est X qui est utilisé, pas Y. » Une réfutation sourcée est une réponse complète.
            - Si ni les sources ni une recherche complémentaire dans la base ne couvrent le sujet, \
            dis-le en une phrase et propose la piste la plus proche, sans rien inventer.

            Pour les questions factuelles sur les merge requests (comptage, tri, la plus ancienne, \
            par sujet), utilise les tools. Termine toujours par les sources utilisées : leur numéro \
            et leur référence (page, fichier, MR, issue).""";

    /**
     * Prompt du client de synthèse (fiches projet, extractions longues) : les documents
     * générés ne portent ni le plafond de concision du chat ni sa règle d'ancrage — le
     * partage du prompt de chat étranglait les fiches « premium » à ~200 mots (revue
     * 2026-07, H5).
     */
    static final String SYNTHESIS_SYSTEM = """
            Tu rédiges des documents techniques internes en français, complets, structurés et \
            fidèles aux informations fournies. Développe chaque section avec toutes les \
            informations disponibles — pas de limite de longueur. N'invente rien : ce que les \
            informations fournies ne couvrent pas est signalé en une ligne. Termine par les \
            sources utilisées.""";

    /** Client du chat RAG (prompt d'ancrage + concision). {@code @Primary} : l'injection par défaut. */
    @Bean
    @Primary
    public ChatClient ragChatClient(TeamConfig config,
                                    ObjectProvider<OllamaChatModel> ollama,
                                    ObjectProvider<GoogleGenAiChatModel> gemini) {
        return clientBuilder(config, ollama, gemini).defaultSystem(SYSTEM_PROMPT).build();
    }

    /** Client des générations longues (fiches projet, extraction de topics) — sans le prompt de chat. */
    @Bean("synthesisChatClient")
    public ChatClient synthesisChatClient(TeamConfig config,
                                          ObjectProvider<OllamaChatModel> ollama,
                                          ObjectProvider<GoogleGenAiChatModel> gemini) {
        return clientBuilder(config, ollama, gemini).defaultSystem(SYNTHESIS_SYSTEM).build();
    }

    private ChatClient.Builder clientBuilder(TeamConfig config,
                                             ObjectProvider<OllamaChatModel> ollama,
                                             ObjectProvider<GoogleGenAiChatModel> gemini) {
        String provider = config.llm().provider();
        return switch (provider) {
            case "ollama" -> ChatClient.builder(require(ollama.getIfAvailable(), provider))
                    // num_ctx : la fenêtre Ollama par défaut (2048) tronquait silencieusement les
                    // premières sources du prompt RAG (cause du hedging) ; 8192 couvre large.
                    // Température basse : réponses factuelles ; repeat_penalty/top_p contre le
                    // décodage dégénéré du 7B (paragraphes dupliqués) sans sacrifier le factuel.
                    .defaultOptions(OllamaChatOptions.builder()
                            .model(config.llm().model())
                            .numCtx(8192)
                            .temperature(0.1)
                            .repeatPenalty(1.1)
                            .topP(0.9));
            case "gemini" -> ChatClient.builder(require(gemini.getIfAvailable(), provider));
            default -> throw new IllegalStateException(
                    "llm.provider non supporté : " + provider + " (attendu : ollama | gemini)");
        };
    }

    @Bean
    public TopicExtractor topicExtractor(@Qualifier("synthesisChatClient") ChatClient chatClient) {
        return new LlmTopicExtractor(chatClient);
    }

    @Bean
    public GraphEnrichmentService graphEnrichmentService(ChunkRepository chunks, GraphRepository graph,
                                                         TopicExtractor topicExtractor, AliasResolver aliases) {
        return new GraphEnrichmentService(chunks, graph, topicExtractor, aliases);
    }

    @Bean
    public EntityDetector entityDetector(GraphSearchRepository graphSearch) {
        return new EntityDetector(graphSearch);
    }

    @Bean
    public MergeRequestTools mergeRequestTools(MergeRequestRepository mergeRequests, TeamConfig config) {
        return new MergeRequestTools(mergeRequests, config.synonyms());
    }

    @Bean
    public KnowledgeBaseTools knowledgeBaseTools(ChunkRepository chunks) {
        return new KnowledgeBaseTools(chunks);
    }

    @Bean
    public ModelRouter modelRouter(TeamConfig config) {
        // Le fallback ne s'applique qu'en local : sur un provider distant, le
        // modèle principal est déjà rapide et le nom qwen n'aurait aucun sens.
        String fallback = "ollama".equals(config.llm().provider()) ? config.llm().fallbackModel() : null;
        return new ModelRouter(fallback);
    }

    @Bean
    public RagChatService ragChatService(ChatClient chatClient, EmbeddingModel embeddingModel,
                                         EntityDetector entityDetector, GraphSearchRepository graphSearch,
                                         ChunkRepository chunks, MergeRequestTools mergeRequestTools,
                                         KnowledgeBaseTools knowledgeBaseTools, ModelRouter modelRouter) {
        return new RagChatService(chatClient, embeddingModel, entityDetector, graphSearch,
                chunks, mergeRequestTools, knowledgeBaseTools, modelRouter);
    }

    private static <T extends ChatModel> T require(T model, String provider) {
        if (model == null) {
            throw new IllegalStateException("Aucun ChatModel disponible pour llm.provider=" + provider
                    + " — vérifier la configuration Spring AI correspondante.");
        }
        return model;
    }
}
