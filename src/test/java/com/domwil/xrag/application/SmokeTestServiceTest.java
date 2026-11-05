package com.domwil.xrag.application;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmokeTestServiceTest {

    private final AliasResolver aliases = new AliasResolver(Map.of("fpssuite", List.of("fps-suite")));
    private final RagChatService chat = mock(RagChatService.class);
    private final SmokeTestService smokeTests = new SmokeTestService(aliases, chat);

    @Test
    void reussiQuandToutesLesQuestionsRepondent() {
        when(chat.answer(any(), any())).thenReturn(Flux.just("Réponse ", "sourcée."));

        SmokeTestService.Report report = smokeTests.run();

        assertThat(report.passed()).isTrue();
        assertThat(report.summary().lines()).hasSize(2).allMatch(line -> line.startsWith("OK"));
    }

    @Test
    void enEchecDesQuUneQuestionEchoue() {
        when(chat.answer(startsWith("Explique-moi"), any())).thenReturn(Flux.just("Réponse."));
        when(chat.answer(startsWith("Quelle merge request"), any())).thenReturn(Flux.empty());

        SmokeTestService.Report report = smokeTests.run();

        assertThat(report.passed()).isFalse();
        assertThat(report.summary()).contains("KO (réponse vide)");
    }

    @Test
    void uneExceptionCompteCommeUnEchec() {
        when(chat.answer(any(), any())).thenReturn(Flux.error(new IllegalStateException("Ollama HS")));

        SmokeTestService.Report report = smokeTests.run();

        assertThat(report.passed()).isFalse();
        assertThat(report.summary()).contains("KO (Ollama HS)");
    }
}
