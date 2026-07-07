package com.domwil.xrag.extraction;

import com.domwil.xrag.application.AliasResolver;
import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.GraphNode;
import com.domwil.xrag.domain.model.MergeRequestMeta;

import java.util.Map;

/**
 * Relations extraites des Merge Requests : MODIFIES vers les fichiers touchés
 * (nœuds CLASS) et REFERENCES vers les clés Jira du titre/description et le
 * projet porteur.
 */
public class MergeRequestGraphMapper {

    private final AliasResolver aliases;

    public MergeRequestGraphMapper(AliasResolver aliases) {
        this.aliases = aliases;
    }

    public ExtractionResult map(MergeRequestMeta mr) {
        GraphNode mrNode = new GraphNode("mr:" + mr.id(), GraphNode.Types.MR, mr.title(),
                Map.of("state", mr.state(), "url", mr.webUrl() == null ? "" : mr.webUrl()));
        String projectId = aliases.projectIdFor(mr.project());
        var result = ExtractionResult.builder();
        result.node(mrNode).linkDocumentTo(mrNode.id());
        result.edge(mrNode, aliases.projectNode(projectId), GraphEdge.Types.REFERENCES)
                .linkDocumentTo(projectId);

        for (String file : mr.changedFiles()) {
            GraphNode fileNode = GraphNode.of("class:" + mr.project() + "/" + file,
                    GraphNode.Types.CLASS, file);
            result.edge(mrNode, fileNode, GraphEdge.Types.MODIFIES);
        }
        for (String issueKey : JiraKeys.in(mr.title() + "\n" + mr.description())) {
            GraphNode issue = GraphNode.of("issue:" + issueKey, GraphNode.Types.ISSUE, issueKey);
            result.edge(mrNode, issue, GraphEdge.Types.REFERENCES).linkDocumentTo(issue.id());
        }
        return result.build();
    }
}
