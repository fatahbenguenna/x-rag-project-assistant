package com.domwil.xrag.domain.model;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Sous-graphe de voisinage (profondeur 2) injecté au prompt et utilisé pour booster la recherche. */
public record Subgraph(List<GraphNode> nodes, List<GraphEdge> edges) {

    public Subgraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public static Subgraph empty() {
        return new Subgraph(List.of(), List.of());
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public Set<String> nodeIds() {
        return nodes.stream().map(GraphNode::id).collect(Collectors.toUnmodifiableSet());
    }
}
