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
        Schedule schedule,
        Extractors extractors
) {

    public TeamConfig {
        aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
        schedule = schedule == null ? new Schedule(null) : schedule;
        extractors = extractors == null ? new Extractors(true, true, false) : extractors;
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

    public record Schedule(String nightly) {
        public Schedule {
            nightly = nightly == null ? "0 0 2 * * *" : nightly;
        }
    }

    public record Extractors(boolean java, boolean typescript, boolean python) {
    }
}
