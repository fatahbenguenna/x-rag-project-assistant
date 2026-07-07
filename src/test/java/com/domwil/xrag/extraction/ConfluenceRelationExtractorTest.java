package com.domwil.xrag.extraction;

import com.domwil.xrag.application.AliasResolver;
import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.SourceDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfluenceRelationExtractorTest {

    private final AliasResolver aliases = new AliasResolver(Map.of(
            "easyloc", List.of("Easy Loc", "easy-loc")));
    private final ConfluenceRelationExtractor extractor = new ConfluenceRelationExtractor(aliases);

    @Test
    void pageDocumentsMentionedProjectsAndReferencesJiraIssues() {
        var doc = new SourceDocument("confluence", null, "12345",
                "Architecture Easy Loc",
                "Suite au bug PASS-123, Easy Loc persiste désormais les commandes.",
                "https://confluence/x", "7", null, Map.of("space", "ARCHI"));

        ExtractionResult result = extractor.extract(doc);

        assertThat(result.edges())
                .contains(
                        GraphEdge.of("page:12345", "project:easyloc", GraphEdge.Types.DOCUMENTS),
                        GraphEdge.of("page:12345", "issue:PASS-123", GraphEdge.Types.REFERENCES));
        assertThat(result.documentNodeIds())
                .contains("page:12345", "project:easyloc", "issue:PASS-123");
    }
}
