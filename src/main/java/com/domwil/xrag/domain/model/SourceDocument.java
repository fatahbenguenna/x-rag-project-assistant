package com.domwil.xrag.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * Document brut remonté par un {@code SourceConnector}, avant chunking/embedding.
 *
 * @param source    type de source : confluence | gitlab-code | jira | project-sheet
 * @param project   id canonique du projet rattaché (peut être null si non résolu)
 * @param path      identifiant stable dans la source (id de page, chemin de fichier, clé d'issue)
 * @param version   version indexée (version.number Confluence, SHA git, updated Jira)
 * @param metadata  propriétés spécifiques à la source (espace, branche, liens d'issues...)
 */
public record SourceDocument(
        String source,
        String project,
        String path,
        String title,
        String content,
        String url,
        String version,
        Instant updatedAt,
        Map<String, Object> metadata
) {

    public SourceDocument {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Préfixe de clé de chunk stable ("source:path"), complété par ":chunk_index" à l'indexation. */
    public String chunkKeyPrefix() {
        return source + ":" + path;
    }
}
