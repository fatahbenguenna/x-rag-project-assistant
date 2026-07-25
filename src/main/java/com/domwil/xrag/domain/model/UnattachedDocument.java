package com.domwil.xrag.domain.model;

import java.util.List;

/**
 * Document candidat à l'enrichissement LLM (décision d'architecture n°10) : sans
 * rattachement ou sans nœud TOPIC — typiquement hors du périmètre des extracteurs
 * déterministes (YAML, MD, JSON, source non Java/TS).
 *
 * @param text            échantillon de contenu (chunks concaténés, tronqué)
 * @param existingNodeIds nœuds NON-topic déjà rattachés aux chunks du document
 *                        (page:/issue:/class:) — les topics extraits y sont reliés,
 *                        et non plus au nœud PROJECT global (hub, revue 2026-07 H2)
 */
public record UnattachedDocument(String source, String project, String path, String title,
                                 String text, List<String> existingNodeIds) {

    public UnattachedDocument {
        existingNodeIds = existingNodeIds == null ? List.of() : List.copyOf(existingNodeIds);
    }
}
