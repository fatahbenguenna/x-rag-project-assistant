package com.domwil.xrag.extraction;

import com.domwil.xrag.application.AliasResolver;
import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.GraphNode;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.RelationExtractor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extraction déterministe côté TypeScript/Angular (regex) :
 * <ul>
 *   <li>appels HttpClient sur des URLs d'environnement
 *       ({@code environment.epsilonUrl}) → CALLS_API vers le projet résolu par alias ;</li>
 *   <li>imports inter-libs ({@code from '@scope/lib'} ou {@code libs/xxx}) → DEPENDS_ON.</li>
 * </ul>
 */
public class TypeScriptRelationExtractor implements RelationExtractor {

    /** environment.epsilonUrl / environment.easylocApiBaseUrl → "epsilon", "easyloc". */
    private static final Pattern ENV_URL =
            Pattern.compile("environment\\.(\\w+?)(?:Api)?(?:Base)?(?:Url|Uri|Endpoint)\\b");
    private static final Pattern LIB_IMPORT =
            Pattern.compile("from\\s+['\"](?:@[\\w-]+/([\\w-]+)|.*?libs/([\\w-]+))");

    private final AliasResolver aliases;

    public TypeScriptRelationExtractor(AliasResolver aliases) {
        this.aliases = aliases;
    }

    @Override
    public boolean supports(SourceDocument document) {
        return "gitlab-code".equals(document.source()) && document.path().endsWith(".ts");
    }

    @Override
    public ExtractionResult extract(SourceDocument document) {
        String projectId = aliases.projectIdFor(document.project());
        GraphNode project = aliases.projectNode(projectId);
        var result = ExtractionResult.builder();
        result.node(project).linkDocumentTo(projectId);

        Matcher envUrls = ENV_URL.matcher(document.content());
        while (envUrls.find()) {
            String target = envUrls.group(1);
            GraphNode dst = aliases.resolveProjectId(target)
                    .map(aliases::projectNode)
                    .orElse(GraphNode.of("endpoint:" + AliasResolver.normalize(target),
                            GraphNode.Types.ENDPOINT, target));
            result.edge(project, dst, GraphEdge.Types.CALLS_API).linkDocumentTo(dst.id());
        }

        Matcher imports = LIB_IMPORT.matcher(document.content());
        while (imports.find()) {
            String lib = imports.group(1) != null ? imports.group(1) : imports.group(2);
            aliases.resolveProjectId(lib).ifPresent(dstId -> {
                if (!dstId.equals(projectId)) {
                    result.edge(project, aliases.projectNode(dstId), GraphEdge.Types.DEPENDS_ON)
                            .linkDocumentTo(dstId);
                }
            });
        }
        return result.build();
    }
}
