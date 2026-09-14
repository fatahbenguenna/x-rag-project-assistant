package com.domwil.xrag.domain.port;

import com.domwil.xrag.domain.model.SourceDocument;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Port d'ingestion d'une source documentaire (Confluence, GitLab, Jira...).
 * Les adapters sont activés par la configuration d'équipe : ajouter une source
 * = implémenter ce port, sans toucher au pipeline d'ingestion.
 */
public interface SourceConnector {

    /**
     * Source du CODE (fichiers des dépôts), commune aux deux transports — API GitLab et dépôts
     * git locaux : mêmes clés de chunk ({@code gitlab-code:<slug>@<branche>/<fichier>}), mêmes
     * extracteurs Java/TS, mêmes citations. Basculer de mode ne duplique donc aucun chunk.
     */
    String CODE_SOURCE = "gitlab-code";

    /** Identifiant du type de source ("confluence", "gitlab-code", "jira"). */
    String source();

    /**
     * Remonte les documents modifiés depuis {@code since}. Un connecteur PEUT rendre un
     * sur-ensemble (récolte complète) : l'idempotence est garantie par {@code version} —
     * l'ingestion saute les documents dont la version indexée n'a pas changé.
     *
     * @param since borne basse de la sync incrémentale ; {@code null} = récolte complète
     */
    List<SourceDocument> fetchChangedSince(Instant since);

    /**
     * Variante en flux : chaque document est remis au {@code sink} dès qu'il est lu, sans
     * accumuler toute la récolte en mémoire. Défaut : parcourt {@link #fetchChangedSince} ;
     * les connecteurs à gros volumes (dépôts git locaux) la redéfinissent.
     */
    default void streamChangedSince(Instant since, Consumer<SourceDocument> sink) {
        fetchChangedSince(since).forEach(sink);
    }
}
