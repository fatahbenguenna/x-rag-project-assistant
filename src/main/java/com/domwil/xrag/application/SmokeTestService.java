package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.GraphNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Smoke test de fin de batch : questions canoniques instanciées depuis la
 * configuration (projets déclarés), passées dans le vrai pipeline RAG.
 * Résultat loggé — sert de notification de santé de l'index.
 */
public class SmokeTestService {

    private static final Logger log = LoggerFactory.getLogger(SmokeTestService.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_QUESTIONS = 3;

    private final AliasResolver aliases;
    private final RagChatService ragChatService;

    public SmokeTestService(AliasResolver aliases, RagChatService ragChatService) {
        this.aliases = aliases;
        this.ragChatService = ragChatService;
    }

    /** Rapport lisible (une ligne par question) et verdict global : vrai si toutes les questions sont OK. */
    public record Report(String summary, boolean passed) {
    }

    /**
     * Bloquant (jusqu'à {@code TIMEOUT} par question) : à appeler hors de l'event loop
     * WebFlux, où {@code block()} lève une exception.
     */
    public Report run() {
        var lines = new ArrayList<String>();
        boolean passed = true;
        List<GraphNode> projects = aliases.declaredProjectNodes();
        var questions = new ArrayList<String>();
        for (GraphNode project : projects.subList(0, Math.min(MAX_QUESTIONS, projects.size()))) {
            questions.add("Explique-moi le projet " + project.name() + " en 3 principes.");
        }
        questions.add("Quelle merge request ouverte est la plus vieille ?");
        for (String question : questions) {
            String line = ask(question);
            passed &= line.startsWith("OK");
            lines.add(line);
        }

        String summary = String.join("\n", lines);
        log.info("Smoke test {} :\n{}", passed ? "réussi" : "EN ÉCHEC", summary);
        return new Report(summary, passed);
    }

    private String ask(String question) {
        long start = System.currentTimeMillis();
        try {
            List<String> parts = ragChatService.answer(question, null).collectList().block(TIMEOUT);
            String answer = parts == null ? "" : String.join("", parts);
            long elapsed = (System.currentTimeMillis() - start) / 1000;
            return (answer.isBlank() ? "KO (réponse vide)" : "OK")
                    + " en " + elapsed + " s — " + question;
        } catch (Exception e) {
            return "KO (" + e.getMessage() + ") — " + question;
        }
    }
}
