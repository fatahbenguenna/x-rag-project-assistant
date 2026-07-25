package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.model.Subgraph;
import com.domwil.xrag.domain.port.ChunkRepository;
import com.domwil.xrag.domain.port.GraphSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Harness d'évaluation du retrieval (recall@k) — action M-8 de la revue 2026-07.
 * Pour chaque cas configuré (question canonique + sous-chaîne identifiant la source
 * attendue), il rejoue le retrieval RÉEL du chat (détection d'entités → voisinage
 * graphe → embedding → recherche hybride) et mesure le RANG de la source attendue
 * dans les {@value #EVAL_DEPTH} premiers candidats. Sans LLM : rapide, déterministe,
 * objectif — le prérequis pour calibrer les poids de la recherche hybride (jamais
 * calibrés) et mesurer le gain réel d'un reranker (avant/après).
 */
public class RagEvalService {

    private static final Logger log = LoggerFactory.getLogger(RagEvalService.class);

    /** Profondeur d'inspection : le vivier de candidats qu'un reranker verrait. */
    static final int EVAL_DEPTH = 40;
    private static final int GRAPH_DEPTH = 2;

    /** Cas d'évaluation : la source attendue est identifiée par une sous-chaîne de son path ou titre. */
    public record EvalCase(String question, String expected) {
    }

    /** Rang 1-indexé de la source attendue ({@code 0} = absente du top-{@value #EVAL_DEPTH}). */
    public record CaseResult(String question, String expected, int rank, String topSource) {
    }

    public record Report(int total, long foundAt4, long foundAt8, long foundAt40,
                         List<CaseResult> results) {

        public static Report of(List<CaseResult> results) {
            return new Report(results.size(),
                    results.stream().filter(r -> r.rank() >= 1 && r.rank() <= 4).count(),
                    results.stream().filter(r -> r.rank() >= 1 && r.rank() <= 8).count(),
                    results.stream().filter(r -> r.rank() >= 1).count(),
                    List.copyOf(results));
        }

        /** Résumé texte pour les logs et la notification du batch nocturne. */
        public String summary() {
            if (total == 0) {
                return "Éval retrieval : aucun cas configuré (team-config eval.cases).";
            }
            var sb = new StringBuilder("Éval retrieval : recall@4 %d/%d · recall@8 %d/%d · recall@40 %d/%d"
                    .formatted(foundAt4, total, foundAt8, total, foundAt40, total));
            for (CaseResult r : results) {
                sb.append("\n- ").append(r.rank() >= 1 ? "rang " + r.rank() : "ABSENTE")
                        .append(" — « ").append(r.question()).append(" » (attendu : ")
                        .append(r.expected()).append(")");
            }
            return sb.toString();
        }
    }

    private final EntityDetector entityDetector;
    private final GraphSearchRepository graphSearch;
    private final EmbeddingModel embeddingModel;
    private final ChunkRepository chunks;
    private final List<EvalCase> cases;

    public RagEvalService(EntityDetector entityDetector, GraphSearchRepository graphSearch,
                          EmbeddingModel embeddingModel, ChunkRepository chunks,
                          List<EvalCase> cases) {
        this.entityDetector = entityDetector;
        this.graphSearch = graphSearch;
        this.embeddingModel = embeddingModel;
        this.chunks = chunks;
        this.cases = List.copyOf(cases);
    }

    public boolean hasCases() {
        return !cases.isEmpty();
    }

    public Report evaluate() {
        var results = new ArrayList<CaseResult>(cases.size());
        for (EvalCase evalCase : cases) {
            results.add(evaluate(evalCase));
        }
        Report report = Report.of(results);
        log.info(report.summary());
        return report;
    }

    /** Même séquence de retrieval que le chat (RagChatService), arrêtée aux candidats. */
    private CaseResult evaluate(EvalCase evalCase) {
        Set<String> seeds = entityDetector.detectNodeIds(evalCase.question());
        Subgraph subgraph = graphSearch.neighborhood(seeds, GRAPH_DEPTH);
        float[] embedding = embeddingModel.embed(evalCase.question());
        List<ScoredChunk> candidates = chunks.hybridSearch(
                embedding, evalCase.question(), subgraph.nodeIds(), null, EVAL_DEPTH);

        String needle = evalCase.expected().toLowerCase(Locale.ROOT);
        int rank = 0;
        for (int i = 0; i < candidates.size(); i++) {
            if (matches(candidates.get(i), needle)) {
                rank = i + 1;
                break;
            }
        }
        String top = candidates.isEmpty() ? "(aucun candidat)" : candidates.getFirst().citation();
        return new CaseResult(evalCase.question(), evalCase.expected(), rank, top);
    }

    private static boolean matches(ScoredChunk chunk, String needle) {
        return (chunk.path() != null && chunk.path().toLowerCase(Locale.ROOT).contains(needle))
                || (chunk.title() != null && chunk.title().toLowerCase(Locale.ROOT).contains(needle));
    }
}
