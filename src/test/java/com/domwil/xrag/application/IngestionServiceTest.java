package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.Chunk;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.ChunkRepository;
import com.domwil.xrag.domain.port.GraphRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionServiceTest {

    private final ChunkRepository chunks = mock(ChunkRepository.class);
    private final EmbeddingModel embeddings = mock(EmbeddingModel.class);
    private final IngestionService ingestion =
            new IngestionService(List.of(), mock(GraphRepository.class), chunks, embeddings);

    @Test
    @SuppressWarnings("unchecked")
    void retireLesCaracteresNulRefusesParPostgres() {
        // Fichier UTF-16 versionné (export SSMS) lu comme du texte : un U+0000 par caractère.
        String utf16 = "S\u0000E\u0000L\u0000E\u0000C\u0000T\u0000 1\u0000";
        when(chunks.indexedVersion(anyString(), anyString())).thenReturn(Optional.empty());
        when(embeddings.embed(anyList())).thenReturn(List.of(new float[]{1f}));

        boolean indexed = ingestion.ingest(new SourceDocument("gitlab-code", "fps-pos", "db/export.sql",
                "export\u0000.sql", utf16, null, "sha", null, null), Set.of(), false);

        ArgumentCaptor<List<Chunk>> upserted = ArgumentCaptor.forClass(List.class);
        verify(chunks).upsert(upserted.capture());
        assertThat(indexed).isTrue();
        assertThat(upserted.getValue()).singleElement().satisfies(chunk -> {
            assertThat(chunk.content()).isEqualTo("SELECT 1");
            assertThat(chunk.title()).isEqualTo("export.sql");
        });
    }
}
