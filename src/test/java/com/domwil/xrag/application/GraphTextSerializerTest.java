package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.GraphNode;
import com.domwil.xrag.domain.model.Subgraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphTextSerializerTest {

    @Test
    void serializesOneRelationPerLineWithNamesAndTypes() {
        var subgraph = new Subgraph(
                List.of(
                        GraphNode.of("project:easyloc", "PROJECT", "Easy Loc"),
                        GraphNode.of("topic:orders", "TOPIC", "orders"),
                        GraphNode.of("project:epsilon", "PROJECT", "Epsilon")),
                List.of(
                        GraphEdge.of("project:easyloc", "topic:orders", "PUBLISHES"),
                        GraphEdge.of("project:epsilon", "topic:orders", "CONSUMES")));

        String text = GraphTextSerializer.serialize(subgraph);

        assertThat(text).isEqualTo("""
                Easy Loc [PROJECT] -PUBLISHES-> orders [TOPIC]
                Epsilon [PROJECT] -CONSUMES-> orders [TOPIC]""");
    }

    @Test
    void emptySubgraphSerializesToEmptyString() {
        assertThat(GraphTextSerializer.serialize(Subgraph.empty())).isEmpty();
    }
}
