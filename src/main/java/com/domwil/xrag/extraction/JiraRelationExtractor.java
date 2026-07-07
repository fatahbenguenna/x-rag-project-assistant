package com.domwil.xrag.extraction;

import com.domwil.xrag.application.AliasResolver;
import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.GraphNode;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.RelationExtractor;

import java.util.List;

/**
 * Relations extraites des issues Jira : issue → projet (rattachement) et
 * liens entre issues (LINKS_TO), transportés en métadonnées par le connecteur.
 */
public class JiraRelationExtractor implements RelationExtractor {

    private final AliasResolver aliases;

    public JiraRelationExtractor(AliasResolver aliases) {
        this.aliases = aliases;
    }

    @Override
    public boolean supports(SourceDocument document) {
        return "jira".equals(document.source());
    }

    @Override
    public ExtractionResult extract(SourceDocument document) {
        String issueKey = document.path();
        GraphNode issue = new GraphNode("issue:" + issueKey, GraphNode.Types.ISSUE,
                document.title(), java.util.Map.of(
                        "status", String.valueOf(document.metadata().getOrDefault("status", "")),
                        "type", String.valueOf(document.metadata().getOrDefault("issueType", ""))));
        var result = ExtractionResult.builder();
        result.node(issue).linkDocumentTo(issue.id());

        Object jiraProject = document.metadata().get("jiraProject");
        if (jiraProject != null) {
            String projectId = aliases.projectIdFor(jiraProject.toString());
            result.edge(issue, aliases.projectNode(projectId), GraphEdge.Types.REFERENCES)
                    .linkDocumentTo(projectId);
        }
        for (String projectId : aliases.projectsMentionedIn(document.content())) {
            result.edge(issue, aliases.projectNode(projectId), GraphEdge.Types.REFERENCES)
                    .linkDocumentTo(projectId);
        }
        if (document.metadata().get("linkedIssues") instanceof List<?> linkedKeys) {
            for (Object linked : linkedKeys) {
                GraphNode other = GraphNode.of("issue:" + linked, GraphNode.Types.ISSUE, linked.toString());
                result.edge(issue, other, GraphEdge.Types.LINKS_TO);
            }
        }
        return result.build();
    }
}
