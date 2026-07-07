package com.domwil.xrag.extraction;

import com.domwil.xrag.application.AliasResolver;
import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.GraphNode;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.RelationExtractor;

/**
 * Relations extraites des pages Confluence : la page DOCUMENTS les projets
 * mentionnés (via alias) et REFERENCES les issues Jira détectées par regex.
 */
public class ConfluenceRelationExtractor implements RelationExtractor {

    private final AliasResolver aliases;

    public ConfluenceRelationExtractor(AliasResolver aliases) {
        this.aliases = aliases;
    }

    @Override
    public boolean supports(SourceDocument document) {
        return "confluence".equals(document.source());
    }

    @Override
    public ExtractionResult extract(SourceDocument document) {
        GraphNode page = new GraphNode("page:" + document.path(), GraphNode.Types.PAGE,
                document.title(), java.util.Map.of("url", document.url() == null ? "" : document.url()));
        var result = ExtractionResult.builder();
        result.node(page).linkDocumentTo(page.id());

        String text = document.title() + "\n" + document.content();
        for (String projectId : aliases.projectsMentionedIn(text)) {
            result.edge(page, aliases.projectNode(projectId), GraphEdge.Types.DOCUMENTS)
                    .linkDocumentTo(projectId);
        }
        for (String issueKey : JiraKeys.in(text)) {
            GraphNode issue = GraphNode.of("issue:" + issueKey, GraphNode.Types.ISSUE, issueKey);
            result.edge(page, issue, GraphEdge.Types.REFERENCES).linkDocumentTo(issue.id());
        }
        return result.build();
    }
}
