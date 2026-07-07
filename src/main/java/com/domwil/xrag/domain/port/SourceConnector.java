package com.domwil.xrag.domain.port;

import com.domwil.xrag.domain.model.SourceDocument;

import java.time.Instant;
import java.util.List;

/**
 * Port d'ingestion d'une source documentaire (Confluence, GitLab, Jira...).
 * Les adapters sont activés par la configuration d'équipe : ajouter une source
 * = implémenter ce port, sans toucher au pipeline d'ingestion.
 */
public interface SourceConnector {

    /** Identifiant du type de source ("confluence", "gitlab-code", "jira"). */
    String source();

    /**
     * Remonte les documents modifiés depuis {@code since}.
     *
     * @param since borne basse de la sync incrémentale ; {@code null} = récolte complète
     */
    List<SourceDocument> fetchChangedSince(Instant since);
}
