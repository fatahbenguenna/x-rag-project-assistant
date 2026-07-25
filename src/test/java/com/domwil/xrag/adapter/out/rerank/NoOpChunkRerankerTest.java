package com.domwil.xrag.adapter.out.rerank;

import com.domwil.xrag.domain.model.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpChunkRerankerTest {

    private static ScoredChunk chunk(String id) {
        return new ScoredChunk(id, "s", "p", "path", "t", "c", null, 0.5);
    }

    private final NoOpChunkReranker reranker = new NoOpChunkReranker();

    @Test
    void sansRerankerLeVivierEstLaTailleFinale() {
        assertThat(reranker.candidatePoolSize(8)).isEqualTo(8);
    }

    @Test
    void passePlatConserveLOrdreEtTronqueATopK() {
        var candidates = List.of(chunk("a"), chunk("b"), chunk("c"));
        assertThat(reranker.rerank("q", candidates, 2))
                .extracting(ScoredChunk::id).containsExactly("a", "b");
        assertThat(reranker.rerank("q", candidates, 5)).isSameAs(candidates);
    }
}
