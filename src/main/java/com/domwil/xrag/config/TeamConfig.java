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
        retrieval = retrieval == null ? new Retrieval(null, null) : retrieval;
        eval = eval == null ? new Eval(List.of()) : eval;
        schedule = schedule == null ? new Schedule(null) : schedule;
        extractors = extractors == null ? new Extractors(true, true, false, false) : extractors;
    }

    public record Llm(
            @NotBlank String provider,
            @NotBlank String model,
            String fallbackModel,
            String embeddingModel
    ) {
        public Llm {
            embeddingModel = embeddingModel == null ? "bge-m3" : embeddingModel;
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
    public record Retrieval(Integer chunkLimit, Integer chunkExcerptChars) {
        public Retrieval {
            chunkLimit = chunkLimit == null ? 8 : chunkLimit;
            chunkExcerptChars = chunkExcerptChars == null ? 1800 : chunkExcerptChars;
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
    public record Extractors(boolean java, boolean typescript, boolean python, boolean llm) {
    }
}
