package com.domwil.xrag.adapter.out.llm;

import com.domwil.xrag.domain.port.TopicExtractor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Adapter LLM (ChatClient) du port {@link TopicExtractor} : demande au modèle les sujets
 * principaux d'un document et normalise la réponse (une entité par ligne). Appel bloquant
 * ({@code .call()}) — s'exécute dans le batch nocturne, hors event-loop réactif.
 */
public class LlmTopicExtractor implements TopicExtractor {

    private static final int MAX_TOPICS = 5;
    private static final int MAX_TEXT_CHARS = 3000;
    private static final int MAX_TOPIC_WORDS = 4;

    // Surcharge par requête le system prompt RAG (« cite tes sources ») : sinon le modèle
    // ajoute une ligne « Sources : … » qui polluerait les sujets.
    private static final String EXTRACTION_SYSTEM = """
            Tu es un extracteur de sujets. Tu réponds UNIQUEMENT par la liste demandée
            (un sujet par ligne), sans introduction, sans sources, sans commentaire.""";

    private static final String PROMPT = """
            Analyse ce document technique et extrais ses SUJETS principaux : composants, entités,
            concepts ou technologies qu'il traite. Contraintes STRICTES de format :
            - de 1 à %d sujets, UN PAR LIGNE ;
            - noms courts (1 à 3 mots), en minuscules ;
            - PAS de numérotation, PAS de puce, PAS de ponctuation, PAS de phrase, PAS d'explication ;
            - si aucun sujet technique n'est identifiable, réponds uniquement : aucun

            Titre : %s
            Contenu :
            %s
            """;

    private final ChatClient chatClient;

    public LlmTopicExtractor(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public List<String> extractTopics(String title, String text) {
        String content = text == null ? "" : text.length() > MAX_TEXT_CHARS
                ? text.substring(0, MAX_TEXT_CHARS) : text;
        String response = chatClient.prompt()
                .system(EXTRACTION_SYSTEM)
                .user(PROMPT.formatted(MAX_TOPICS, title == null ? "" : title, content))
                .call()
                .content();
        return parse(response);
    }

    /** Normalise la réponse du LLM en sujets exploitables (minuscules, sans bruit de format). */
    static List<String> parse(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        return Arrays.stream(response.split("\\r?\\n"))
                .map(LlmTopicExtractor::clean)
                .filter(LlmTopicExtractor::isTopic)
                .distinct()
                .limit(MAX_TOPICS)
                .toList();
    }

    /** Un sujet valable : non vide, court, sans « : » (ligne de citation/label) ni excès de mots. */
    private static boolean isTopic(String topic) {
        return !topic.isBlank()
                && !"aucun".equals(topic)
                && topic.length() <= 40
                && !topic.contains(":")
                && topic.split("\\s+").length <= MAX_TOPIC_WORDS;
    }

    /** Retire puces, numéros et ponctuation de bord ; passe en minuscules. */
    private static String clean(String line) {
        return line.toLowerCase(Locale.ROOT)
                .replaceAll("^[\\s\\-*•·.\\d)]+", "")
                .replaceAll("[\\s.,;:!?)\\]]+$", "")
                .trim();
    }
}
