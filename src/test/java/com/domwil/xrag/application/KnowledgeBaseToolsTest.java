package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.port.ChunkRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseToolsTest {

    private final ChunkRepository chunks = mock(ChunkRepository.class);
    private final KnowledgeBaseTools tools = new KnowledgeBaseTools(chunks);

    private static ScoredChunk chunk(String source, String path, String title, String content, String url) {
        return new ScoredChunk("id", source, "fps-suite", path, title, content, url, 0.9);
    }

    @Test
    void rechercheEtFormateAvecCitationEtExtrait() {
        when(chunks.keywordSearch(eq("RoleAuthority sécurité"), isNull(), anyInt()))
                .thenReturn(List.of(chunk("jira", "FPSSUITE-2", "Fusion rôles",
                        "contenu de l'issue", "https://jira/FPSSUITE-2")));

        String result = tools.searchKnowledgeBase("RoleAuthority sécurité", null, null);

        assertThat(result).contains("issue FPSSUITE-2")
                .contains("contenu de l'issue")
                .contains("https://jira/FPSSUITE-2");
    }

    @Test
    void messageExpliciteQuandAucunResultat() {
        when(chunks.keywordSearch(any(), any(), anyInt())).thenReturn(List.of());

        assertThat(tools.searchKnowledgeBase("inexistant", null, null))
                .contains("Aucun").contains("inexistant");
    }

    @Test
    void limiteParDefautA5EtProjetVideTraiteCommeNull() {
        when(chunks.keywordSearch(any(), any(), anyInt())).thenReturn(List.of());

        tools.searchKnowledgeBase("x", "  ", null);

        verify(chunks).keywordSearch(eq("x"), isNull(), eq(5));
    }

    @Test
    void plafonneLaLimiteA10() {
        when(chunks.keywordSearch(any(), any(), anyInt())).thenReturn(List.of());

        tools.searchKnowledgeBase("x", "fps-suite", 99);

        verify(chunks).keywordSearch(eq("x"), eq("fps-suite"), eq(10));
    }
}
