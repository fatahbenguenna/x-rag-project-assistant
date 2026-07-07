package com.domwil.xrag.domain.model;

/** Chunk retourné par la recherche hybride, avec son score combiné. */
public record ScoredChunk(
        String id,
        String source,
        String project,
        String path,
        String title,
        String content,
        String url,
        double score
) {

    /** Référence courte pour la citation de sources dans les réponses. */
    public String citation() {
        return switch (source) {
            case "confluence" -> "page Confluence « " + (title == null ? path : title) + " »";
            case "gitlab-code" -> "fichier " + path;
            case "gitlab-mr" -> "MR " + path;
            case "jira" -> "issue " + path;
            case "project-sheet" -> "fiche projet " + (project == null ? path : project);
            default -> source + ":" + path;
        };
    }
}
