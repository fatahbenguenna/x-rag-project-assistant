package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.ExtractionResult;
import com.domwil.xrag.domain.model.UnattachedDocument;
import com.domwil.xrag.domain.port.ChunkRepository;
import com.domwil.xrag.domain.port.GraphRepository;
import com.domwil.xrag.domain.port.TopicExtractor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphEnrichmentServiceTest {

    private final ChunkRepository chunks = mock(ChunkRepository.class);
    private final GraphRepository graph = mock(GraphRepository.class);
    private final TopicExtractor topics = mock(TopicExtractor.class);
    private final AliasResolver aliases = new AliasResolver(Map.of("fps-suite", List.of("FPSSUITE")));
    private final GraphEnrichmentService service =
            new GraphEnrichmentService(chunks, graph, topics, aliases);

    private static UnattachedDocument doc(String path) {
        return new UnattachedDocument("gitlab-code", "fps-suite", path, "titre", "contenu kafka kds");
    }

    @Test
    void creeLesTopicsAliasesEtRattacheLesChunks() {
        when(chunks.unattachedDocuments(anyInt())).thenReturn(List.of(doc("apps/deploy.yml")));
        when(topics.extractTopics(any(), any())).thenReturn(List.of("Kafka", "KDS"));
        when(chunks.attachToNodes(eq("gitlab-code"), eq("apps/deploy.yml"), any())).thenReturn(3);

        GraphEnrichmentService.Report report = service.enrich(10);

        assertThat(report.documentsEnriched()).isEqualTo(1);
        assertThat(report.topicNodes()).isEqualTo(2);
        assertThat(report.chunksAttached()).isEqualTo(3);

        ArgumentCaptor<ExtractionResult> upserted = ArgumentCaptor.forClass(ExtractionResult.class);
        verify(graph).upsert(upserted.capture());
        assertThat(upserted.getValue().nodes())
                .anyMatch(n -> n.id().equals("topic:kafka") && "TOPIC".equals(n.type()) && "Kafka".equals(n.name()))
                .anyMatch(n -> n.id().equals("topic:kds"));
        // arête topic -> projet, avec le nœud projet nommé « FPSSUITE » (pas le slug)
        assertThat(upserted.getValue().edges())
                .anyMatch(e -> e.src().equals("topic:kafka") && e.dst().equals("project:fps-suite"));
        assertThat(upserted.getValue().nodes())
                .anyMatch(n -> n.id().equals("project:fps-suite") && "FPSSUITE".equals(n.name()));

        verify(graph).upsertAliases(argThat(m -> "topic:kafka".equals(m.get("kafka"))
                && "topic:kds".equals(m.get("kds"))));
        verify(chunks).attachToNodes(eq("gitlab-code"), eq("apps/deploy.yml"),
                argThat(ids -> ids.contains("topic:kafka") && ids.contains("topic:kds")));
    }

    @Test
    void unDocumentSansSujetNestPasRattache() {
        when(chunks.unattachedDocuments(anyInt())).thenReturn(List.of(doc("apps/vide.md")));
        when(topics.extractTopics(any(), any())).thenReturn(List.of());

        GraphEnrichmentService.Report report = service.enrich(10);

        assertThat(report.documentsEnriched()).isZero();
        verify(chunks, never()).attachToNodes(any(), any(), any());
        verify(graph, never()).upsert(any());
    }

    @Test
    void unEchecLlmSurUnDocumentNInterromptPasLeLot() {
        when(chunks.unattachedDocuments(anyInt())).thenReturn(List.of(doc("a.yml"), doc("b.yml")));
        when(topics.extractTopics(any(), any()))
                .thenThrow(new RuntimeException("LLM indisponible"))
                .thenReturn(List.of("resilience"));
        when(chunks.attachToNodes(any(), any(), any())).thenReturn(2);

        GraphEnrichmentService.Report report = service.enrich(10);

        assertThat(report.documentsSeen()).isEqualTo(2);
        assertThat(report.documentsEnriched()).isEqualTo(1);
        assertThat(report.chunksAttached()).isEqualTo(2);
    }
}
