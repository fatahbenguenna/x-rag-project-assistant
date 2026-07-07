package com.domwil.xrag.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sortie d'un extracteur : nœuds/arêtes à upserter dans le graphe, plus les
 * ids de nœuds à rattacher aux chunks du document (colonne node_ids, pont
 * RAG <-> graphe).
 */
public record ExtractionResult(
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        Set<String> documentNodeIds
) {

    public ExtractionResult {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        documentNodeIds = Set.copyOf(documentNodeIds);
    }

    public static ExtractionResult empty() {
        return new ExtractionResult(List.of(), List.of(), Set.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return nodes.isEmpty() && edges.isEmpty();
    }

    public static final class Builder {
        private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
        private final List<GraphEdge> edges = new ArrayList<>();
        private final Set<String> documentNodeIds = new LinkedHashSet<>();

        public Builder node(GraphNode node) {
            nodes.putIfAbsent(node.id(), node);
            return this;
        }

        /** Ajoute une arête en garantissant l'existence des deux nœuds (FK). */
        public Builder edge(GraphNode src, GraphNode dst, String type) {
            node(src);
            node(dst);
            edges.add(GraphEdge.of(src.id(), dst.id(), type));
            return this;
        }

        public Builder linkDocumentTo(String nodeId) {
            documentNodeIds.add(nodeId);
            return this;
        }

        public ExtractionResult build() {
            return new ExtractionResult(List.copyOf(nodes.values()), edges, documentNodeIds);
        }
    }
}
