package com.domwil.xrag.adapter.in.web;

import com.domwil.xrag.application.RagChatService;
import com.domwil.xrag.config.TeamConfig;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiCompatControllerTest {

    private final RagChatService rag = mock(RagChatService.class);
    private final OpenAiCompatController controller = new OpenAiCompatController(
            rag, JsonMapper.builder().build(), config());

    private static TeamConfig config() {
        return new TeamConfig("passerelle",
                new TeamConfig.Llm("ollama", "qwen2.5:7b-instruct", null, null),
                new TeamConfig.Sources(null, null, null), null, null, null, null);
    }

    @Test
    void listeLeModeleDeLEquipe() {
        var models = controller.models();
        assertThat(models.data()).hasSize(1);
        assertThat(models.data().getFirst().id()).isEqualTo("xrag-passerelle");
    }

    @Test
    void streamAuFormatOpenAiAvecDone() {
        when(rag.answer(eq("Explique Elog"), isNull())).thenReturn(Flux.just("Bon", "jour"));

        var response = controller.chatCompletions(new OpenAiCompatController.ChatCompletionRequest(
                "xrag-passerelle",
                List.of(new OpenAiCompatController.Message("user", "Explique Elog")),
                true));

        List<String> events = response.getBody().collectList().block();
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/event-stream");
        assertThat(events.getFirst()).contains("\"role\":\"assistant\"");
        assertThat(events).anySatisfy(e -> assertThat(e).contains("\"content\":\"Bon\""));
        assertThat(events).anySatisfy(e -> assertThat(e).contains("\"finish_reason\":\"stop\""));
        assertThat(events.getLast()).isEqualTo("[DONE]");
    }

    @Test
    void reponseCompleteSansStream() {
        when(rag.answer(eq("Explique Elog"), isNull())).thenReturn(Flux.just("Bon", "jour"));

        var response = controller.chatCompletions(new OpenAiCompatController.ChatCompletionRequest(
                "xrag-passerelle",
                List.of(new OpenAiCompatController.Message("system", "tu es concis"),
                        new OpenAiCompatController.Message("user", "Explique Elog")),
                false));

        List<String> body = response.getBody().collectList().block();
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/json");
        assertThat(body).hasSize(1);
        assertThat(body.getFirst())
                .contains("\"object\":\"chat.completion\"")
                .contains("\"content\":\"Bonjour\"");
    }

    @Test
    void prendLaDerniereQuestionUtilisateur() {
        when(rag.answer(eq("seconde"), isNull())).thenReturn(Flux.just("ok"));

        var response = controller.chatCompletions(new OpenAiCompatController.ChatCompletionRequest(
                null,
                List.of(new OpenAiCompatController.Message("user", "première"),
                        new OpenAiCompatController.Message("assistant", "réponse"),
                        new OpenAiCompatController.Message("user", "seconde")),
                false));
        assertThat(response.getBody().collectList().block().getFirst()).contains("ok");
    }

    @Test
    void requeteSansMessageUserRejetee() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                controller.chatCompletions(new OpenAiCompatController.ChatCompletionRequest(
                        null, List.of(new OpenAiCompatController.Message("system", "x")), false)));
    }
}
