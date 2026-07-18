package com.domwil.xrag.domain.model;

/**
 * Document dont les chunks ne sont rattachés à aucun nœud du graphe (node_ids vide) —
 * typiquement un fichier non couvert par les extracteurs déterministes (YAML, MD, JSON,
 * source non Java/TS). Candidat à l'enrichissement LLM nocturne (décision d'architecture n°10).
 *
 * @param text échantillon de contenu (chunks concaténés, tronqué) pour l'extraction de sujets
 */
public record UnattachedDocument(String source, String project, String path, String title, String text) {
}
