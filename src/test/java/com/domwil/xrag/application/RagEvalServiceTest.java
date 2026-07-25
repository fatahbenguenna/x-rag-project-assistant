package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.model.Subgraph;
import com.domwil.xrag.adapter.out.rerank.NoOpChunkReranker;
import com.domwil.xrag.domain.port.ChunkRepository;
import com.domwil.xrag.domain.port.GraphSearchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagEvalServiceTest {

    private final EntityDetector entityDetector = mock(EntityDetector.class);
    private final GraphSearchRepository graphSearch = mock(GraphSearchRepository.class);
    private final EmbeddingModel embeddings = mock(EmbeddingModel.class);
    private final ChunkRepository chunks = mock(ChunkRepository.class);

    private static ScoredChunk chunk(String path, String title) {
        return new ScoredChunk("id-" + path, "confluence", "fps", path, title, "contenu", null, 0.5);
    }

    private RagEvalService service(List<RagEvalService.EvalCase> cases) {
        when(entityDetector.detectNodeIds(anyString())).thenReturn(java.util.Set.of());
        when(graphSearch.neighborhood(any(), anyInt())).thenReturn(Subgraph.empty());
        when(embeddings.embed(anyString())).thenReturn(new float[]{1f});
        return new RagEvalService(entityDetector, graphSearch, embeddings, chunks,
                new NoOpChunkReranker(), 8, cases);
    }

    @Test
    void mesureLeRangDeLaSourceAttendueEtLesRecalls() {
        var service = service(List.of(
                new RagEvalService.EvalCase("q1", "spec-040"),      // rang 1 (par path)
                new RagEvalService.EvalCase("q2", "environnements"), // rang 5 (par titre, casse ignorée)
                new RagEvalService.EvalCase("q3", "introuvable")));   // absente
        when(chunks.hybridSearch(any(), anyString(), any(), any(), anyInt(), org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(List.of(
                        chunk("specs/spec-040/plan.md", "Plan"),
                        chunk("a", "A"), chunk("b", "B"), chunk("c", "C"),
                        chunk("d", "5.2 Environnements (DEV)")));

        RagEvalService.Report report = service.evaluate();

        assertThat(report.total()).isEqualTo(3);
        assertThat(report.foundAt4()).isEqualTo(1);   // q1 seulement (rang 1)
        assertThat(report.foundAt8()).isEqualTo(2);   // q1 + q2 (rang 5)
        assertThat(report.foundAt40()).isEqualTo(2);  // q3 absente
        assertThat(report.results().get(1).rank()).isEqualTo(5);
        assertThat(report.results().get(2).rank()).isZero();
        assertThat(report.summary()).contains("recall@4 1/3").contains("recall@8 2/3").contains("ABSENTE");
    }

    @Test
    void signaleLesRetrogradationsDuReranker() {
        // Un reranker actif qui ÉJECTE la source attendue du top-K livré : le harness doit
        // le voir (avant correctif, la ligne était identique à un run sain — revue adversariale).
        var rerankerActif = mock(com.domwil.xrag.domain.port.ChunkReranker.class);
        when(rerankerActif.candidatePoolSize(org.mockito.ArgumentMatchers.anyInt())).thenReturn(40);
        when(rerankerActif.rerank(anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(chunk("autre", "Autre"))); // la source attendue est éjectée
        when(entityDetector.detectNodeIds(anyString())).thenReturn(java.util.Set.of());
        when(graphSearch.neighborhood(any(), anyInt())).thenReturn(Subgraph.empty());
        when(embeddings.embed(anyString())).thenReturn(new float[]{1f});
        when(chunks.hybridSearch(any(), anyString(), any(), any(), anyInt(), org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(List.of(chunk("specs/spec-040/plan.md", "Plan"))); // rang 1 du vivier
        var service = new RagEvalService(entityDetector, graphSearch, embeddings, chunks,
                rerankerActif, 8, List.of(new RagEvalService.EvalCase("q", "spec-040")));

        RagEvalService.Report report = service.evaluate();

        assertThat(report.rerankActive()).isTrue();
        assertThat(report.foundAt4()).isEqualTo(1);       // vivier : trouvé
        assertThat(report.rerankFoundAt4()).isZero();      // livré : éjecté
        assertThat(report.summary()).contains("RÉTROGRADÉE par le rerank")
                .contains("Après rerank");
    }

    @Test
    void sansCasConfiguresLeHarnessEstInactif() {
        var service = service(List.of());
        assertThat(service.hasCases()).isFalse();
        assertThat(service.evaluate().summary()).contains("aucun cas configuré");
    }
}
