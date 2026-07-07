package com.domwil.xrag.domain.model;

import java.time.Instant;
import java.util.List;

/** Métadonnées structurées d'une Merge Request GitLab (table merge_requests, tools SQL). */
public record MergeRequestMeta(
        String id,                 // "gitlab:<project_id>:<iid>"
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
}
