package com.domwil.xrag.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * Métadonnées structurées d'une Merge Request (table merge_requests, tools SQL) — issue de
 * l'API GitLab ou reconstituée depuis l'historique d'un dépôt git local.
 *
 * @param id  {@code gitlab:<project_id>:<iid>} (API) ; {@code git:<slug>:<iid>},
 *            {@code git:<slug>:commits:<sha>} ou {@code git-branch:<slug>:<branche>} (git local)
 * @param iid numéro de la MR sur la forge ; {@code 0} = inconnu (MR reconstituée sans référence) —
 *            jamais cherchée par numéro, affichée par son id ({@link #reference()})
 */
public record MergeRequestMeta(
        String id,
        String project,            // id canonique du projet
        long iid,
        String title,
        String description,
        String state,              // opened | merged | closed | locked
        String author,
        String sourceBranch,
        String targetBranch,
        String webUrl,
        List<String> labels,
        List<String> changedFiles, // fichiers touchés (arêtes MODIFIES du graphe)
        Instant createdAt,
        Instant updatedAt,
        Instant mergedAt
) {

    public MergeRequestMeta {
        labels = labels == null ? List.of() : List.copyOf(labels);
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
    }

    /** Référence lisible : {@code !153} quand le numéro est connu, sinon l'id. */
    public String reference() {
        return iid > 0 ? "!" + iid : id;
    }
}
