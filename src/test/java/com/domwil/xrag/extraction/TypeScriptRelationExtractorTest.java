package com.domwil.xrag.extraction;

import com.domwil.xrag.application.AliasResolver;
import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.SourceDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TypeScriptRelationExtractorTest {

    private final AliasResolver aliases = new AliasResolver(Map.of(
            "easyloc", List.of("Easy Loc", "easy-loc"),
            "epsilon", List.of("Epsilon", "epsilon-service")));
    private final TypeScriptRelationExtractor extractor = new TypeScriptRelationExtractor(aliases);

    @Test
    void extractsHttpCallsOnEnvironmentUrlsAndLibImports() {
        String ts = """
                import { EpsilonModel } from '@passerelle/epsilon';
                import { helper } from '../../libs/easy-loc/src/helper';

                export class OrderService {
                  constructor(private http: HttpClient) {}
                  load() {
                    return this.http.get<Order[]>(`${environment.epsilonUrl}/api/orders`);
                  }
                }
                """;
        ExtractionResult result = extractor.extract(
                new SourceDocument("gitlab-code", "front-orders", "front-orders@main/src/order.service.ts",
                        "src/order.service.ts", ts, null, "sha", null, Map.of()));

        assertThat(result.edges())
                .contains(
                        GraphEdge.of("project:frontorders", "project:epsilon", GraphEdge.Types.CALLS_API),
                        GraphEdge.of("project:frontorders", "project:epsilon", GraphEdge.Types.DEPENDS_ON),
                        GraphEdge.of("project:frontorders", "project:easyloc", GraphEdge.Types.DEPENDS_ON));
    }

    @Test
    void ignoresUnknownImportsButKeepsUnresolvedEndpoints() {
        ExtractionResult result = extractor.extract(
                new SourceDocument("gitlab-code", "front", "front@main/a.ts", "a.ts",
                        "import { x } from '@angular/core';\nthis.http.get(`${environment.legacyBillingUrl}/x`);",
                        null, "sha", null, Map.of()));

        assertThat(result.edges())
                .contains(GraphEdge.of("project:front", "endpoint:legacybilling", GraphEdge.Types.CALLS_API));
        assertThat(result.edges()).extracting("type").doesNotContain(GraphEdge.Types.DEPENDS_ON);
    }
}
