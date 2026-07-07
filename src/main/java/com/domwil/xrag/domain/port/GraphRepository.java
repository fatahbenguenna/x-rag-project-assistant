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
}
