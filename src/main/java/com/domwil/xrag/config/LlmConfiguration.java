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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

            Le contexte fourni fait autorité. Le bloc <graphe>, les sources numérotées du bloc \
            <documents> et les résultats des tools sont le fruit d'une recherche déjà effectuée pour \
            toi dans les systèmes de l'équipe : traite-les comme fiables et à jour. Dès qu'une source \
            concerne la question, réponds à partir de son contenu et cite-la.

            RÈGLE ABSOLUE : si une source du contexte concerne le sujet demandé, même partiellement, \
            commence ta réponse par « Oui » puis réponds à partir de cette source. Dans ce cas, il \
            t'est INTERDIT d'écrire « Non », « Aucune information », « il n'y a pas » ou « pas \
            d'information spécifique » : ce serait contredire une source que tu viens de trouver.

            Marche à suivre :
            - Repère les sources qui traitent du sujet demandé, puis construis ta réponse à partir de \
            leur contenu (résume, relie, explique).
            - Termine toujours par les sources utilisées : leur numéro et leur référence (page, \
            fichier, MR, issue).
            - Pour les questions factuelles sur les merge requests (comptage, tri, la plus ancienne \
            MR ouverte, MRs liées à un sujet), appuie-toi sur les tools.

            Tu ES l'interface de Confluence, Jira et GitLab : ne renvoie jamais l'utilisateur les \
            consulter « directement ». Quand une source correspond au sujet, commence par « Oui » et \
            expose-la — ne conclus pas qu'il n'existe « aucune information ». Ne signale une absence \
            que si, vraiment, aucune source ni aucun tool ne concerne la question ; dans ce cas, dis-le \
            en une phrase et propose la reformulation ou le projet le plus proche, sans rien inventer.

            Exemple.
            Question : « Y a-t-il un ticket sur la fusion des rôles et de la sécurité (RoleAuthority) ? »
            Contexte : [1] issue FPSSUITE-2 — « Fusionner RoleAuthority et la gestion des rôles… »
            Réponse attendue : « Oui. L'issue FPSSUITE-2 porte sur la fusion de RoleAuthority avec la \
            gestion de la sécurité : [points clés de l'extrait]. Source : [1] issue FPSSUITE-2. »""";

    @Bean
    public ChatClient chatClient(TeamConfig config,
                                 ObjectProvider<OllamaChatModel> ollama,
                                 ObjectProvider<GoogleGenAiChatModel> gemini) {
        String provider = config.llm().provider();
        return switch (provider) {
            case "ollama" -> ChatClient.builder(require(ollama.getIfAvailable(), provider))
                    .defaultSystem(SYSTEM_PROMPT)
                    // num_ctx : la fenêtre Ollama par défaut (2048) tronquait silencieusement les
                    // premières sources du prompt RAG (cause du hedging) ; 8192 couvre large.
                    // Température basse : réponses factuelles, moins de disclaimers spéculatifs.
                    .defaultOptions(OllamaChatOptions.builder()
                            .model(config.llm().model())
                            .numCtx(8192)
                            .temperature(0.1))
                    .build();
            case "gemini" -> ChatClient.builder(require(gemini.getIfAvailable(), provider))
                    .defaultSystem(SYSTEM_PROMPT)
                    .build();
            default -> throw new IllegalStateException(
                    "llm.provider non supporté : " + provider + " (attendu : ollama | gemini)");
        };
    }

    @Bean
    public TopicExtractor topicExtractor(ChatClient chatClient) {
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
