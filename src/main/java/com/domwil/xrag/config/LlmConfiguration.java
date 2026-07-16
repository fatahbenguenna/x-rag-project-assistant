package com.domwil.xrag.config;

import com.domwil.xrag.application.EntityDetector;
import com.domwil.xrag.application.MergeRequestTools;
import com.domwil.xrag.application.ModelRouter;
import com.domwil.xrag.application.RagChatService;
import com.domwil.xrag.domain.port.ChunkRepository;
import com.domwil.xrag.domain.port.GraphSearchRepository;
import com.domwil.xrag.domain.port.MergeRequestRepository;
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
            Tu es l'assistant technique de l'équipe. Tu réponds en français, de façon concise \
            (maximum ~200 mots pour une réponse descriptive), en t'appuyant UNIQUEMENT sur le \
            contexte fourni (graphe de relations, extraits de documents) et sur les tools.
            Cite toujours tes sources (page Confluence, fichier, MR, issue) en fin de réponse.
            Pour les questions factuelles sur les merge requests (comptages, tris, la plus \
            vieille MR ouverte...), utilise les tools plutôt que les extraits.
            Si le contexte ne permet pas de répondre, dis-le explicitement.""";

    @Bean
    public ChatClient chatClient(TeamConfig config,
                                 ObjectProvider<OllamaChatModel> ollama,
                                 ObjectProvider<GoogleGenAiChatModel> gemini) {
        String provider = config.llm().provider();
        return switch (provider) {
            case "ollama" -> ChatClient.builder(require(ollama.getIfAvailable(), provider))
                    .defaultSystem(SYSTEM_PROMPT)
                    .defaultOptions(OllamaChatOptions.builder().model(config.llm().model()))
                    .build();
            case "gemini" -> ChatClient.builder(require(gemini.getIfAvailable(), provider))
                    .defaultSystem(SYSTEM_PROMPT)
                    .build();
            default -> throw new IllegalStateException(
                    "llm.provider non supporté : " + provider + " (attendu : ollama | gemini)");
        };
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
                                         ModelRouter modelRouter) {
        return new RagChatService(chatClient, embeddingModel, entityDetector, graphSearch,
                chunks, mergeRequestTools, modelRouter);
    }

    private static <T extends ChatModel> T require(T model, String provider) {
        if (model == null) {
            throw new IllegalStateException("Aucun ChatModel disponible pour llm.provider=" + provider
                    + " — vérifier la configuration Spring AI correspondante.");
        }
        return model;
    }
}
