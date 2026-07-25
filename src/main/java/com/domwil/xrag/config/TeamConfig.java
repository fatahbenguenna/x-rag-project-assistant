package com.domwil.xrag.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;

/**
 * Contrat de configuration d'une instance d'équipe, chargé depuis team-config.yml
 * (voir team-config.example.yml). Tout est piloté ici : aucun nom de projet en dur.
 */
@ConfigurationProperties
@Validated
public record TeamConfig(
        @NotBlank String team,
        @NotNull @Valid Llm llm,
        @NotNull @Valid Sources sources,
        Map<String, List<String>> aliases,
        Map<String, List<String>> synonyms,
        Retrieval retrieval,
        Eval eval,
        Schedule schedule,
        Extractors extractors
) {

    public TeamConfig {
        aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
        synonyms = synonyms == null ? Map.of() : Map.copyOf(synonyms);
        retrieval = retrieval == null ? new Retrieval(null, null, null) : retrieval;
        eval = eval == null ? new Eval(List.of()) : eval;
        schedule = schedule == null ? new Schedule(null) : schedule;
        extractors = extractors == null ? new Extractors(true, true, false, false, null) : extractors;
    }

    /**
     * @param numCtx      fenêtre de contexte Ollama — le défaut serveur (2048-4096) tronque
     *                    silencieusement le prompt RAG ; 8192 couvre large. Sans objet pour
     *                    un provider distant. Mémoire KV cache ≈ proportionnelle : voir
     *                    OLLAMA_KV_CACHE_TYPE dans .env.example sur machine contrainte.
     * @param temperature bas = factuel (défaut 0.1) — appliqué à TOUS les providers (la
     *                    température par défaut de Gemini (~1.0) perdrait le cadrage factuel)
     */
    public record Llm(
            @NotBlank String provider,
            @NotBlank String model,
            String fallbackModel,
            String embeddingModel,
            Integer numCtx,
            Double temperature
    ) {
        public Llm {
            embeddingModel = embeddingModel == null ? "bge-m3" : embeddingModel;
            numCtx = numCtx == null ? 8192 : numCtx;
            temperature = temperature == null ? 0.1 : temperature;
        }
    }

    public record Sources(
            @Valid Confluence confluence,
            @Valid Gitlab gitlab,
            @Valid Jira jira
    ) {
    }

    public record Confluence(@NotBlank String baseUrl, @NotEmpty List<String> spaces) {
    }

    public record Gitlab(@NotBlank String baseUrl, @NotBlank String group, List<String> branches) {
        public Gitlab {
            branches = branches == null || branches.isEmpty() ? List.of("main") : List.copyOf(branches);
        }
    }

    public record Jira(@NotBlank String baseUrl, @NotEmpty List<String> projects) {
    }

    /**
     * Réglages du retrieval injecté au prompt. Défauts calibrés pour num_ctx=8192 :
     * 8 chunks montrés en entier (1800 car. = la taille max d'un chunk — l'ancienne
     * troncature à 900 cachait 62-81 % du contenu au LLM, revue 2026-07). Repli si la
     * latence CPU se dégrade : chunk-limit: 6, chunk-excerpt-chars: 1600.
     */
    public record Retrieval(Integer chunkLimit, Integer chunkExcerptChars, Reranker reranker) {
        public Retrieval {
            chunkLimit = chunkLimit == null ? 8 : chunkLimit;
            chunkExcerptChars = chunkExcerptChars == null ? 1800 : chunkExcerptChars;
            reranker = reranker == null ? new Reranker(null, null, null, null, null, null, null) : reranker;
        }
    }

    /**
     * Reranker cross-encoder ONNX in-process (bge-reranker-v2-m3 int8). DÉSACTIVÉ par
     * défaut : coût CPU (~3-8 s pour 40 paires sur la cible) + modèle de 571 Mo à
     * télécharger (bootstrap.sh, volume reranker-models). {@code candidates} = taille du
     * vivier reclassé.
     */
    public record Reranker(Boolean enabled, String modelPath, String modelFile,
                           Integer maxLength, Integer candidates, Integer batchSize,
                           Integer intraOpThreads) {
        public Reranker {
            enabled = enabled != null && enabled;
            modelPath = modelPath == null ? "/models/reranker" : modelPath;
            modelFile = modelFile == null ? "model_quantized" : modelFile;
            maxLength = maxLength == null ? 512 : maxLength;
            candidates = candidates == null ? 40 : candidates;
            batchSize = batchSize == null ? 16 : batchSize;
            intraOpThreads = intraOpThreads == null
                    ? Runtime.getRuntime().availableProcessors() : intraOpThreads;
        }
    }

    /**
     * Cas d'évaluation du retrieval (recall@k) : pour chaque question canonique, la
     * source attendue est identifiée par une sous-chaîne (insensible à la casse) de son
     * path ou de son titre. Mesuré sans LLM (rapide, objectif) — prérequis au calibrage
     * des poids de la recherche hybride et à la mesure du gain d'un reranker.
     */
    public record Eval(List<EvalCase> cases) {
        public Eval {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }

    public record EvalCase(String question, String expected) {
    }

    public record Schedule(String nightly) {
        public Schedule {
            nightly = nightly == null ? "0 0 2 * * *" : nightly;
        }
    }

    /**
     * @param llm enrichissement LLM nocturne du graphe (décision d'architecture n°10) : à
     *            n'activer que si l'éval de qualité montre des trous (chunks non rattachés)
     */
    /**
     * @param llm             enrichissement LLM nocturne du graphe (décision n°10)
     * @param llmMaxDocsPerNight plafond de documents topic-enrichis par nuit (budget de
     *                           temps du batch — le backlog restant suit les nuits suivantes)
     */
    public record Extractors(boolean java, boolean typescript, boolean python, boolean llm,
                             Integer llmMaxDocsPerNight) {
        public Extractors {
            llmMaxDocsPerNight = llmMaxDocsPerNight == null ? 150 : llmMaxDocsPerNight;
        }
    }
}
