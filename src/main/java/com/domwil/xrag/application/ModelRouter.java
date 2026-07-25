package com.domwil.xrag.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Routage par type de question (décision d'architecture n°2) : les questions
 * purement descriptives (résumé, explication d'un projet — servies par les
 * fiches pré-calculées) peuvent être générées par le modèle fallback plus
 * léger (ex. qwen2.5:3b), nettement plus rapide en CPU. Les synthèses
 * trans-projets et les questions factuelles (tools/function calling, où les
 * petits modèles sont faibles) restent sur le modèle principal.
 */
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    /** Tournures descriptives : « explique-moi X », « résume », « c'est quoi », « présente »… */
    private static final Pattern DESCRIPTIVE = Pattern.compile(
            "\\b(explique|expliquer|resume|resumer|decris|decrire|presente|presenter|"
                    + "c'?est quoi|qu'?est[- ]ce que|a quoi sert|principes?|overview|describe|explain|summar)\\w*\\b");

    /** Indices d'une question factuelle/structurée (tools) ou trans-projets : jamais le petit modèle. */
    private static final Pattern NEEDS_MAIN_MODEL = Pattern.compile(
            "\\b(mrs?|merge[- ]requests?|combien|compte|compter|liste|lister|plus (vieille|vieux|recente?|ancienne?)|"
                    + "ouvertes?|communiquer|entre|comparer?|difference|versus|vs)\\b");

    private final ChatOptions fallbackOptions;

    /**
     * @param fallbackOptions options COMPLÈTES du modèle léger (modèle + num_ctx +
     *                        température, construites par la configuration), ou null pour
     *                        désactiver le routage. Complètes obligatoirement :
     *                        {@code ChatClient.options(...)} remplace les options par
     *                        défaut du client — un simple nom de modèle ferait perdre
     *                        num_ctx, et le prompt RAG déborderait la fenêtre par défaut
     *                        du serveur Ollama (troncature silencieuse des sources).
     */
    public ModelRouter(ChatOptions fallbackOptions) {
        this.fallbackOptions = fallbackOptions;
    }

    /**
     * Options de chat à appliquer à la requête, ou {@code null} pour garder le
     * modèle principal par défaut du ChatClient.
     */
    public ChatOptions route(String question) {
        if (fallbackOptions == null || !isDescriptive(question)) {
            return null;
        }
        log.debug("Question descriptive : routée vers le modèle fallback {}", fallbackOptions.getModel());
        return fallbackOptions;
    }

    boolean isDescriptive(String question) {
        String normalized = normalize(question);
        return DESCRIPTIVE.matcher(normalized).find() && !NEEDS_MAIN_MODEL.matcher(normalized).find();
    }

    private static String normalize(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        return java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }
}
