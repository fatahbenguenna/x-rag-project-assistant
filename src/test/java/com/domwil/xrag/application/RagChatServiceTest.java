package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.MergeRequestMeta;
import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.port.ChunkRepository;
import com.domwil.xrag.domain.port.MergeRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Pré-injection des références exactes (clé Jira, numéro de MR citées dans la question)
 * et marquage « RÉFÉRENCE EXACTE » dans le prompt — correctif T8/L3 : le retrieval
 * sémantique ne privilégie pas un identifiant, et sans marquage le modèle 7B noie la
 * référence dans le bruit lexical des autres documents.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class RagChatServiceTest {

    @Mock
    private ChunkRepository chunks;
    @Mock
    private MergeRequestRepository mergeRequests;

    private RagChatService service() {
        return new RagChatService(null, null, null, null, chunks, mergeRequests,
                null, null, null, null, 8, 1800);
    }

    private static ScoredChunk chunk(String id, String source, String path) {
        return new ScoredChunk(id, source, "fps", path, "titre " + id, "contenu " + id, null, 0.5);
    }

    private static MergeRequestMeta mr153() {
        return new MergeRequestMeta("gitlab:1:153", "fps-suite", 153,
                "feat(fpskds): connexion backend réelle — WebSocket STOMP",
                "## Contexte\nPR attitrée au suivi de connexion backend.",
                "merged", "fatah", "feat/kds", "main",
                "https://gitlab.example/mr/153", List.of(), List.of(), null, null, null);
    }

    @Test
    void placeLesReferencesExactesEnDernierAdjacentesALaQuestionEtDedupliquees() {
        // en DERNIER : les petits modèles pondèrent la fin du prompt plus que le début
        // (biais de récence mesuré : référence en tête ignorée au profit du bruit)
        var jiraChunk = chunk("jira-fpssuite-2-0", "jira", "FPSSUITE-2");
        when(chunks.documentChunks("jira", "FPSSUITE-2")).thenReturn(List.of(jiraChunk));
        when(mergeRequests.findByIid(153)).thenReturn(Optional.of(mr153()));
        // le retrieval sémantique a déjà remonté le chunk Jira : il ne doit pas doublonner
        var retrieved = List.of(chunk("autre", "confluence", "page-1"), jiraChunk);

        var result = service().withExactReferences(
                "Compare FPSSUITE-2 et la MR !153 s'il te plaît.", retrieved);

        assertThat(result.exactCount()).isEqualTo(2);
        assertThat(result.chunks()).extracting(ScoredChunk::id)
                .containsExactly("autre", "jira-fpssuite-2-0", "mr-exact-153");
        ScoredChunk mrChunk = result.chunks().getLast();
        assertThat(mrChunk.source()).isEqualTo("gitlab-mr");
        assertThat(mrChunk.content())
                .contains("WebSocket STOMP")
                .contains("PR attitrée au suivi de connexion backend");
    }

    @Test
    void avecReferenceExacteLeBruitContextuelEstReduit() {
        // 8 chunks de bruit diluent la référence (échec mesuré : « décris la MR !153 »
        // répondu depuis des chunks d'icônes sans rapport) — on en garde 4 au plus
        when(mergeRequests.findByIid(153)).thenReturn(Optional.of(mr153()));
        var retrieved = List.of(
                chunk("b1", "gitlab-code", "f1"), chunk("b2", "gitlab-code", "f2"),
                chunk("b3", "gitlab-code", "f3"), chunk("b4", "gitlab-code", "f4"),
                chunk("b5", "gitlab-code", "f5"), chunk("b6", "gitlab-code", "f6"));

        var result = service().withExactReferences("Décris la MR !153 en détail.", retrieved);

        assertThat(result.exactCount()).isEqualTo(1);
        assertThat(result.chunks()).extracting(ScoredChunk::id)
                .containsExactly("b1", "b2", "b3", "b4", "mr-exact-153");
    }

    @Test
    void sansIdentifiantLeRetrievalEstInchange() {
        var retrieved = List.of(chunk("a", "confluence", "p1"));

        var result = service().withExactReferences("Comment fonctionne la caisse ?", retrieved);

        assertThat(result.exactCount()).isZero();
        assertThat(result.chunks()).isSameAs(retrieved);
    }

    @Test
    void uneCleEnMinusculesEstNormaliseeAvantLaRecherche() {
        var jiraChunk = chunk("jira-fpssuite-2-0", "jira", "FPSSUITE-2");
        when(chunks.documentChunks("jira", "FPSSUITE-2")).thenReturn(List.of(jiraChunk));

        var result = service().withExactReferences("De quoi parle l'issue fpssuite-2 ?", List.of());

        assertThat(result.exactCount()).isEqualTo(1);
        assertThat(result.chunks()).extracting(ScoredChunk::id)
                .containsExactly("jira-fpssuite-2-0");
    }

    @Test
    void referenceInconnueEnBaseNInjecteRien() {
        when(chunks.documentChunks("jira", "FPSSUITE-999")).thenReturn(List.of());
        var retrieved = List.of(chunk("a", "confluence", "p1"));

        var result = service().withExactReferences("Que dit FPSSUITE-999 ?", retrieved);

        assertThat(result.exactCount()).isZero();
        assertThat(result.chunks()).isSameAs(retrieved);
    }

    @Test
    void formatChunksMarqueUniquementLesReferencesExactesEnFinDeListe() {
        var docs = List.of(
                chunk("contexte", "confluence", "page-1"),
                chunk("exact", "jira", "FPSSUITE-2"));

        String formatted = service().formatChunks(docs, 1);

        assertThat(formatted)
                .contains("[1] page Confluence « titre contexte »")
                .contains("[2] RÉFÉRENCE EXACTE — issue FPSSUITE-2")
                .doesNotContain("[1] RÉFÉRENCE EXACTE");
    }
}
