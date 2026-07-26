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
                        GraphNode.of("project:fpskds", "PROJECT", "FPS KDS"),
                        GraphNode.of("topic:orders", "TOPIC", "orders"),
                        GraphNode.of("project:fps-pos", "PROJECT", "fps-pos")),
                List.of(
                        GraphEdge.of("project:fpskds", "topic:orders", "PUBLISHES"),
                        GraphEdge.of("project:fps-pos", "topic:orders", "CONSUMES")));

        String text = GraphTextSerializer.serialize(subgraph);

        assertThat(text).isEqualTo("""
                FPS KDS [PROJECT] -PUBLISHES-> orders [TOPIC]
                fps-pos [PROJECT] -CONSUMES-> orders [TOPIC]""");
    }

    @Test
    void emptySubgraphSerializesToEmptyString() {
        assertThat(GraphTextSerializer.serialize(Subgraph.empty())).isEmpty();
    }
}
