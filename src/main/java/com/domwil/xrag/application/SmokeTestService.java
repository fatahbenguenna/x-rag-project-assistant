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

    public String run() {
        var report = new ArrayList<String>();
        List<GraphNode> projects = aliases.declaredProjectNodes();
        for (GraphNode project : projects.subList(0, Math.min(MAX_QUESTIONS, projects.size()))) {
            String question = "Explique-moi le projet " + project.name() + " en 3 principes.";
            report.add(ask(question));
        }
        report.add(ask("Quelle merge request ouverte est la plus vieille ?"));

        String summary = String.join("\n", report);
        log.info("Smoke test :\n{}", summary);
        return summary;
    }

    private String ask(String question) {
        long start = System.currentTimeMillis();
        try {
            List<String> parts = ragChatService.answer(question, null).collectList().block(TIMEOUT);
            String answer = parts == null ? "" : String.join("", parts);
            long elapsed = (System.currentTimeMillis() - start) / 1000;
            return (answer == null || answer.isBlank() ? "KO (réponse vide)" : "OK")
                    + " en " + elapsed + " s — " + question;
        } catch (Exception e) {
            return "KO (" + e.getMessage() + ") — " + question;
        }
    }
}
