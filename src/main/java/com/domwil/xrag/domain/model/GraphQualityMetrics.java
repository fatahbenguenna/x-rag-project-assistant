package com.domwil.xrag.domain.model;

import java.util.List;

/**
 * Métriques brutes de couverture du graphe de connaissances, mesurées sur
 * l'index. Servent à objectiver la décision d'architecture n°10 : activer
 * l'extraction LLM nocturne seulement si l'extraction déterministe laisse
 * des trous.
 */
public record GraphQualityMetrics(
        long nodes,
        long edges,
        long orphanNodes,
        long chunks,
        long chunksLinkedToGraph,
        List<String> projectsWithoutStructuralRelations
) {

    /** Part des chunks rattachés à au moins un nœud (pont RAG ↔ graphe). */
    public double linkedChunkRatio() {
        return chunks == 0 ? 0.0 : (double) chunksLinkedToGraph / chunks;
    }

    /** Part des nœuds sans aucune arête. */
    public double orphanNodeRatio() {
        return nodes == 0 ? 0.0 : (double) orphanNodes / nodes;
    }
}
