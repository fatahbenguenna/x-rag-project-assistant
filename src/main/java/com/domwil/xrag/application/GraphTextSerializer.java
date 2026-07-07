package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.GraphNode;
import com.domwil.xrag.domain.model.Subgraph;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sérialise le sous-graphe en texte compact injecté au prompt :
 * une relation par ligne, "Easy Loc [PROJECT] -PUBLISHES-> orders [TOPIC]".
 */
public final class GraphTextSerializer {

    private static final int MAX_LINES = 60;

    private GraphTextSerializer() {
    }

    public static String serialize(Subgraph subgraph) {
        if (subgraph.isEmpty()) {
            return "";
        }
        Map<String, GraphNode> byId = subgraph.nodes().stream()
                .collect(Collectors.toMap(GraphNode::id, Function.identity(), (a, b) -> a));
        return subgraph.edges().stream()
                .limit(MAX_LINES)
                .map(edge -> line(edge, byId))
                .collect(Collectors.joining("\n"));
    }

    private static String line(GraphEdge edge, Map<String, GraphNode> byId) {
        return label(byId.get(edge.src()), edge.src())
                + " -" + edge.type() + "-> "
                + label(byId.get(edge.dst()), edge.dst());
    }

    private static String label(GraphNode node, String fallbackId) {
        return node == null ? fallbackId : node.name() + " [" + node.type() + "]";
    }
}
