package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.model.Subgraph;
import com.domwil.xrag.domain.port.ChunkReranker;
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

    /**
     * @param rank            rang 1-indexé dans le vivier ({@code 0} = absente du top-{@value #EVAL_DEPTH})
     * @param rankAfterRerank rang après reranking ({@code 0} = absente du top-K reclassé, ou reranker inactif)
     */
    public record CaseResult(String question, String expected, int rank, int rankAfterRerank,
                             String topSource) {
    }

    public record Report(int total, long foundAt4, long foundAt8, long foundAt40,
                         boolean rerankActive, long rerankFoundAt4, long rerankFoundAtK,
                         int chunkLimit, List<CaseResult> results) {

        public static Report of(List<CaseResult> results, boolean rerankActive, int chunkLimit) {
            return new Report(results.size(),
                    results.stream().filter(r -> r.rank() >= 1 && r.rank() <= 4).count(),
                    results.stream().filter(r -> r.rank() >= 1 && r.rank() <= 8).count(),
                    results.stream().filter(r -> r.rank() >= 1).count(),
                    rerankActive,
                    results.stream().filter(r -> r.rankAfterRerank() >= 1 && r.rankAfterRerank() <= 4).count(),
                    results.stream().filter(r -> r.rankAfterRerank() >= 1).count(),
                    chunkLimit,
                    List.copyOf(results));
        }

        /**
         * Résumé texte (logs + notification nocturne). Quand le reranker est actif, les
         * recalls POST-rerank sont affichés — c'est le top-K reclassé que le LLM reçoit,
         * pas le vivier — et les RÉTROGRADATIONS (source dans le top-K du vivier mais
         * éjectée par le rerank) sont signalées explicitement (revue adversariale).
         */
        public String summary() {
            if (total == 0) {
                return "Éval retrieval : aucun cas configuré (team-config eval.cases).";
            }
            var sb = new StringBuilder("Éval retrieval : recall@4 %d/%d · recall@8 %d/%d · recall@40 %d/%d"
                    .formatted(foundAt4, total, foundAt8, total, foundAt40, total));
            if (rerankActive) {
                sb.append("\nAprès rerank (le top-%d réellement livré au LLM) : recall@4 %d/%d · recall@%d %d/%d"
                        .formatted(chunkLimit, rerankFoundAt4, total, chunkLimit, rerankFoundAtK, total));
            }
            for (CaseResult r : results) {
                sb.append("\n- ").append(r.rank() >= 1 ? "rang " + r.rank() : "ABSENTE");
                if (rerankActive) {
                    if (r.rankAfterRerank() >= 1) {
                        sb.append(" (rerank : ").append(r.rankAfterRerank()).append(")");
                    } else if (r.rank() >= 1 && r.rank() <= chunkLimit) {
                        sb.append(" (RÉTROGRADÉE par le rerank — hors du top-").append(chunkLimit).append(" livré)");
                    }
                }
                sb.append(" — « ").append(r.question()).append(" » (attendu : ")
                        .append(r.expected()).append(")");
            }
            return sb.toString();
        }
    }

    private final EntityDetector entityDetector;
    private final GraphSearchRepository graphSearch;
    private final EmbeddingModel embeddingModel;
    private final ChunkRepository chunks;
    private final ChunkReranker reranker;
    private final int chunkLimit;
    private final List<EvalCase> cases;

    public RagEvalService(EntityDetector entityDetector, GraphSearchRepository graphSearch,
                          EmbeddingModel embeddingModel, ChunkRepository chunks,
                          ChunkReranker reranker, int chunkLimit, List<EvalCase> cases) {
        this.entityDetector = entityDetector;
        this.graphSearch = graphSearch;
        this.embeddingModel = embeddingModel;
        this.chunks = chunks;
        this.reranker = reranker;
        this.chunkLimit = chunkLimit;
        this.cases = List.copyOf(cases);
    }

    public boolean hasCases() {
        return !cases.isEmpty();
    }

    public Report evaluate() {
        // Figé pour TOUT le run : le chargement asynchrone du modèle pourrait sinon faire
        // basculer l'état en cours d'éval (rapport incohérent, revue adversariale).
        boolean rerankActive = reranker.candidatePoolSize(chunkLimit) > chunkLimit;
        var results = new ArrayList<CaseResult>(cases.size());
        for (EvalCase evalCase : cases) {
            results.add(evaluate(evalCase, rerankActive));
        }
        Report report = Report.of(results, rerankActive, chunkLimit);
        log.info(report.summary());
        return report;
    }

    /** Même séquence de retrieval que le chat (RagChatService), arrêtée aux candidats. */
    private CaseResult evaluate(EvalCase evalCase, boolean rerankActive) {
        Set<String> seeds = entityDetector.detectNodeIds(evalCase.question());
        Subgraph subgraph = graphSearch.neighborhood(seeds, GRAPH_DEPTH);
        float[] embedding = embeddingModel.embed(evalCase.question());
        List<ScoredChunk> candidates = chunks.hybridSearch(
                embedding, evalCase.question(), subgraph.nodeIds(), null, EVAL_DEPTH,
                rerankActive ? 0.1 : 0.3);

        String needle = evalCase.expected().toLowerCase(Locale.ROOT);
        int rank = rankOf(candidates, needle);
        // Rerank sur le sous-vivier que la PROD reclasse réellement (candidatePoolSize peut
        // différer d'EVAL_DEPTH si l'opérateur personnalise reranker.candidates).
        int prodPool = Math.min(reranker.candidatePoolSize(chunkLimit), candidates.size());
        int rankAfterRerank = rerankActive
                ? rankOf(reranker.rerank(evalCase.question(), candidates.subList(0, prodPool), chunkLimit), needle)
                : 0;
        String top = candidates.isEmpty() ? "(aucun candidat)" : candidates.getFirst().citation();
        return new CaseResult(evalCase.question(), evalCase.expected(), rank, rankAfterRerank, top);
    }

    private static int rankOf(List<ScoredChunk> chunks, String needle) {
        for (int i = 0; i < chunks.size(); i++) {
            if (matches(chunks.get(i), needle)) {
                return i + 1;
            }
        }
        return 0;
    }

    private static boolean matches(ScoredChunk chunk, String needle) {
        return (chunk.path() != null && chunk.path().toLowerCase(Locale.ROOT).contains(needle))
                || (chunk.title() != null && chunk.title().toLowerCase(Locale.ROOT).contains(needle));
    }
}
