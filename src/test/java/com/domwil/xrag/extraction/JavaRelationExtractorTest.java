package com.domwil.xrag.extraction;

import com.domwil.xrag.application.AliasResolver;
import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.SourceDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JavaRelationExtractorTest {

    private final AliasResolver aliases = new AliasResolver(Map.of(
            "fpskds", List.of("FPS KDS", "fps-kds", "FPSKDS"),
            "fpspos", List.of("fps-pos", "fps-pos-service")));
    private final JavaRelationExtractor extractor = new JavaRelationExtractor(aliases);

    @Test
    void extractsTablesFeignClientsAndKafkaTopics() {
        String java = """
                package com.example;

                @Entity
                @Table(name = "rental_orders")
                public class RentalOrder { private Long id; }

                @FeignClient(name = "fps-pos-service")
                interface FpsPosClient { String fetch(); }

                class OrderListener {
                    @KafkaListener(topics = {"orders", "billing"})
                    public void onMessage(String message) { }

                    void publish(Object kafkaTemplate) {
                        this.kafkaTemplate.send("orders-events", "payload");
                    }

                    private Object kafkaTemplate;
                }
                """;
        ExtractionResult result = extractor.extract(doc(java));

        assertThat(result.nodes())
                .extracting("id")
                .contains("project:fpskds", "table:rental_orders", "project:fpspos",
                        "topic:orders", "topic:billing", "topic:orders-events");
        assertThat(result.edges())
                .contains(
                        GraphEdge.of("project:fpskds", "table:rental_orders", GraphEdge.Types.SHARES_TABLE),
                        GraphEdge.of("project:fpskds", "project:fpspos", GraphEdge.Types.CALLS_API),
                        GraphEdge.of("project:fpskds", "topic:orders", GraphEdge.Types.CONSUMES),
                        GraphEdge.of("project:fpskds", "topic:billing", GraphEdge.Types.CONSUMES),
                        GraphEdge.of("project:fpskds", "topic:orders-events", GraphEdge.Types.PUBLISHES));
        assertThat(result.documentNodeIds()).contains("project:fpskds", "table:rental_orders");
    }

    @Test
    void entityWithoutTableAnnotationUsesSnakeCaseName() {
        ExtractionResult result = extractor.extract(doc("""
                @Entity
                public class CustomerBillingAccount { }
                """));
        assertThat(result.nodes()).extracting("id").contains("table:customer_billing_account");
    }

    @Test
    void unparseableSourceYieldsNothing() {
        assertThat(extractor.extract(doc("not java at all {{{")).isEmpty()).isTrue();
    }

    private static SourceDocument doc(String content) {
        return new SourceDocument("gitlab-code", "fps-kds", "fps-kds@main/src/A.java",
                "src/A.java", content, null, "sha", null, Map.of());
    }
}
