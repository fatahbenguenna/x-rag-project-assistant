package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.GraphNode;
import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.UnattachedDocument;
import com.domwil.xrag.domain.port.ChunkRepository;
import com.domwil.xrag.domain.port.GraphRepository;
import com.domwil.xrag.domain.port.TopicExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Enrichissement LLM nocturne du graphe (décision d'architecture n°10). Pour les documents
 * non rattachés (node_ids vide — surtout les fichiers hors périmètre des extracteurs
 * déterministes), le LLM extrait des SUJETS qui deviennent des nœuds {@code TOPIC}, reliés au
 * projet et déclarés comme alias, puis les chunks du document leur sont rattachés. Referme le
 * pont RAG↔graphe : une question mentionnant le sujet amorce le nœud → boost des chunks liés.
 *
 * <p>Plafonné par exécution (le batch a un budget de temps) ; résilient (un document en échec
 * n'interrompt pas le lot). Upsert only, jamais de destruction.
 */
public class GraphEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(GraphEnrichmentService.class);

    private final ChunkRepository chunks;
    private final GraphRepository graph;
    private final TopicExtractor topicExtractor;
    private final AliasResolver aliases;

    public GraphEnrichmentService(ChunkRepository chunks, GraphRepository graph,
                                  TopicExtractor topicExtractor, AliasResolver aliases) {
        this.chunks = chunks;
        this.graph = graph;
        this.topicExtractor = topicExtractor;
        this.aliases = aliases;
    }

    /** Bilan d'une passe d'enrichissement, tracé et notifié par le batch nocturne. */
    public record Report(int documentsSeen, int documentsEnriched, int topicNodes, int chunksAttached) {
        @Override
        public String toString() {
            return "%d/%d documents enrichis, %d nœuds TOPIC, %d chunks rattachés"
                    .formatted(documentsEnriched, documentsSeen, topicNodes, chunksAttached);
        }
    }

    /** Enrichit les documents non rattachés (node_ids vide) — les plus gros d'abord (batch nocturne). */
    public Report enrich(int maxDocuments) {
        return enrichAll(chunks.unattachedDocuments(maxDocuments));
    }

    /**
     * Enrichit les documents sans nœud TOPIC des sources données (ex. Confluence/Jira), même
     * déjà rattachés à leur PAGE/ISSUE : densifie leur couverture sémantique. Sources vides = toutes.
     */
    public Report enrichSources(Collection<String> sources, int maxDocuments) {
        return enrichAll(chunks.documentsNeedingTopics(sources, maxDocuments));
    }

    private Report enrichAll(List<UnattachedDocument> documents) {
        int enriched = 0;
        int topicNodes = 0;
        int attached = 0;
        for (UnattachedDocument document : documents) {
            try {
                LinkedHashSet<String> nodeIds = topicNodesFor(document);
                if (nodeIds.isEmpty()) {
                    continue;
                }
                attached += chunks.attachToNodes(document.source(), document.path(), nodeIds);
                topicNodes += nodeIds.size();
                enriched++;
            } catch (Exception e) {
                log.warn("Enrichissement LLM échoué sur {}:{} — {}",
                        document.source(), document.path(), e.getMessage());
            }
        }
        var report = new Report(documents.size(), enriched, topicNodes, attached);
        log.info("Enrichissement LLM du graphe : {}", report);
        return report;
    }

    /**
     * Extrait les sujets du document, crée les nœuds TOPIC (+ arête vers le projet + alias) et
     * renvoie leurs ids à rattacher aux chunks. Ensemble vide si le LLM ne trouve aucun sujet.
     */
    private LinkedHashSet<String> topicNodesFor(UnattachedDocument document) {
        List<String> topics = topicExtractor.extractTopics(document.title(), document.text());
        var builder = ExtractionResult.builder();
        var aliasTable = new HashMap<String, String>();
        var nodeIds = new LinkedHashSet<String>();
        GraphNode projectNode = projectNodeOf(document);
        for (String topic : topics) {
            String slug = AliasResolver.normalize(topic);
            if (slug.isBlank()) {
                continue;
            }
            String topicId = "topic:" + slug;
            GraphNode topicNode = GraphNode.of(topicId, GraphNode.Types.TOPIC, topic);
            builder.node(topicNode);
            if (projectNode != null) {
                builder.edge(topicNode, projectNode, GraphEdge.Types.REFERENCES);
            }
            aliasTable.put(slug, topicId);
            nodeIds.add(topicId);
        }
        if (nodeIds.isEmpty()) {
            return nodeIds;
        }
        graph.upsert(builder.build());
        graph.upsertAliases(aliasTable);
        return nodeIds;
    }

    /** Nœud PROJECT du document, avec son nom d'affichage canonique (jamais écrasé par le slug). */
    private GraphNode projectNodeOf(UnattachedDocument document) {
        if (document.project() == null || document.project().isBlank()) {
            return null;
        }
        return aliases.projectNode(AliasResolver.projectNodeId(document.project()));
    }
}
