package com.domwil.xrag.adapter.in.web;

import com.domwil.xrag.application.RagChatService;
import com.domwil.xrag.config.TeamConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiCompatControllerTest {

    private final RagChatService rag = mock(RagChatService.class);
    private final OpenAiCompatController controller = new OpenAiCompatController(
            rag, JsonMapper.builder().build(), config());

    private static TeamConfig config() {
        return new TeamConfig("passerelle",
                new TeamConfig.Llm("ollama", "qwen2.5:7b-instruct", null, null, null, null),
                new TeamConfig.Sources(null, null, null), null, null, null, null, null, null);
    }

    private static ChatResponse fragment(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse fragmentWithUsage(String text, int promptTokens, int completionTokens) {
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    @Test
    void listeLeModeleDeLEquipe() {
        var models = controller.models();
        assertThat(models.data()).hasSize(1);
        assertThat(models.data().getFirst().id()).isEqualTo("xrag-passerelle");
    }

    @Test
    void streamAuFormatOpenAiAvecDone() {
        when(rag.streamWithUsage(eq("Explique Elog"), isNull()))
                .thenReturn(Flux.just(fragment("Bon"), fragment("jour")));

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
        when(rag.streamWithUsage(eq("Explique Elog"), isNull()))
                .thenReturn(Flux.just(fragment("Bon"), fragment("jour")));

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
    void exposeLUsageDeTokensDansLaReponse() {
        when(rag.streamWithUsage(eq("Explique Elog"), isNull()))
                .thenReturn(Flux.just(fragment("Bon"), fragmentWithUsage("jour", 120, 30)));

        var response = controller.chatCompletions(new OpenAiCompatController.ChatCompletionRequest(
                "xrag-passerelle",
                List.of(new OpenAiCompatController.Message("user", "Explique Elog")),
                false));

        assertThat(response.getBody().collectList().block().getFirst())
                .contains("\"prompt_tokens\":120")
                .contains("\"completion_tokens\":30")
                .contains("\"total_tokens\":150");
    }

    @Test
    void prendLaDerniereQuestionUtilisateur() {
        when(rag.streamWithUsage(eq("seconde"), isNull())).thenReturn(Flux.just(fragment("ok")));

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
