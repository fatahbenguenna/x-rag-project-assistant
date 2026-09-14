package com.domwil.xrag.domain.port;

import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.GraphNode;

import java.util.Collection;
import java.util.Map;

/** Port de persistance du graphe (tables graph_nodes / graph_edges / entity_aliases). */
public interface GraphRepository {

    void upsertNodes(Collection<GraphNode> nodes);

    void upsertEdges(Collection<GraphEdge> edges);

    /** Nœuds d'abord (FK), puis arêtes. */
    default void upsert(ExtractionResult result) {
        upsertNodes(result.nodes());
        upsertEdges(result.edges());
    }

    /** alias normalisé -> id de nœud canonique. */
    void upsertAliases(Map<String, String> aliasToNodeId);

    /**
     * Supprime des nœuds (arêtes et alias compris). Réservé à la réconciliation d'entités
     * dont la source de vérité a disparu (ex. MR « branche ouverte » fusionnée) — l'index
     * documentaire n'est jamais détruit par ce chemin.
     */
    void deleteNodes(Collection<String> nodeIds);
}
