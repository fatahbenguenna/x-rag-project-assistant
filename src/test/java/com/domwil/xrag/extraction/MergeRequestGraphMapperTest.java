package com.domwil.xrag.extraction;

import com.domwil.xrag.application.AliasResolver;
import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.MergeRequestMeta;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MergeRequestGraphMapperTest {

    @Test
    void mapsModifiedFilesAndJiraReferences() {
        var mapper = new MergeRequestGraphMapper(new AliasResolver(Map.of(
                "easyloc", List.of("Easy Loc", "easy-loc"))));
        var mr = new MergeRequestMeta("gitlab:42:7", "easy-loc", 7,
                "PASS-99 corrige la persistance", "Fix du bug PASS-99", "merged",
                "hassen", "fix/persist", "main", "https://gitlab/mr/7",
                List.of("bug"), List.of("src/main/java/Order.java"),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"));

        ExtractionResult result = mapper.map(mr);

        assertThat(result.edges())
                .contains(
                        GraphEdge.of("mr:gitlab:42:7", "project:easyloc", GraphEdge.Types.REFERENCES),
                        GraphEdge.of("mr:gitlab:42:7", "class:easy-loc/src/main/java/Order.java", GraphEdge.Types.MODIFIES),
                        GraphEdge.of("mr:gitlab:42:7", "issue:PASS-99", GraphEdge.Types.REFERENCES));
    }
}
