package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.GraphQualityMetrics;
import com.domwil.xrag.domain.port.GraphQualityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Éval de qualité du graphe (décision d'architecture n°10) : l'extraction de
 * relations est déterministe d'abord ; l'extraction LLM nocturne ne doit être
 * envisagée que si cette éval montre des trous. Le verdict est donc factuel :
 * tant que {@link Report#gaps()} est vide, inutile d'ajouter du LLM.
 */
public class GraphQualityService {

    private static final Logger log = LoggerFactory.getLogger(GraphQualityService.class);

    /** En dessous de cette part de chunks rattachés au graphe, le pont RAG ↔ graphe est troué. */
    static final double MIN_LINKED_CHUNK_RATIO = 0.5;
    /** Au-dessus de cette part de nœuds sans arête, la résolution d'entités fragmente le graphe. */
    static final double MAX_ORPHAN_NODE_RATIO = 0.2;

    public record Report(GraphQualityMetrics metrics, List<String> gaps) {

        public boolean hasGaps() {
            return !gaps.isEmpty();
        }

        public String verdict() {
            return hasGaps()
                    ? "Trous détectés — envisager l'extraction LLM nocturne (décision 10) : "
                            + String.join(" ; ", gaps)
                    : "Pas de trou détecté — l'extraction déterministe suffit, pas d'extraction LLM à activer.";
        }
    }

    private final GraphQualityRepository repository;

    public GraphQualityService(GraphQualityRepository repository) {
        this.repository = repository;
    }

    public Report evaluate() {
        GraphQualityMetrics metrics = repository.measure();
        var gaps = new ArrayList<String>();

        if (metrics.chunks() > 0 && metrics.linkedChunkRatio() < MIN_LINKED_CHUNK_RATIO) {
            gaps.add(String.format("seulement %.0f%% des chunks sont rattachés au graphe (seuil %.0f%%)",
                    metrics.linkedChunkRatio() * 100, MIN_LINKED_CHUNK_RATIO * 100));
        }
        if (metrics.nodes() > 0 && metrics.orphanNodeRatio() > MAX_ORPHAN_NODE_RATIO) {
            gaps.add(String.format("%.0f%% des nœuds sont orphelins (seuil %.0f%%) — vérifier les alias",
                    metrics.orphanNodeRatio() * 100, MAX_ORPHAN_NODE_RATIO * 100));
        }
        if (!metrics.projectsWithoutStructuralRelations().isEmpty()) {
            gaps.add("projets sans relation structurante (DEPENDS_ON/CALLS_API/PUBLISHES/CONSUMES/SHARES_TABLE) : "
                    + String.join(", ", metrics.projectsWithoutStructuralRelations()));
        }

        var report = new Report(metrics, List.copyOf(gaps));
        log.info("Éval graphe : {} nœuds ({} orphelins), {} arêtes, {}/{} chunks rattachés — {}",
                metrics.nodes(), metrics.orphanNodes(), metrics.edges(),
                metrics.chunksLinkedToGraph(), metrics.chunks(), report.verdict());
        return report;
    }
}
