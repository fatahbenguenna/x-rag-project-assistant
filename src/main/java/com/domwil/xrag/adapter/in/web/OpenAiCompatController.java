package com.domwil.xrag.adapter.in.web;

import com.domwil.xrag.application.RagChatService;
import com.domwil.xrag.config.TeamConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Façade OpenAI-compatible minimale ({@code /v1}) pour brancher Open WebUI
 * (ou tout client OpenAI) directement sur le pipeline RAG. Le "modèle" exposé
 * est l'assistant d'équipe complet : graphe + retrieval hybride + tools.
 *
 * <p>Stateless : seule la dernière question utilisateur est traitée, le
 * contexte vient du RAG, pas de l'historique de conversation.
 */
@RestController
@RequestMapping("/v1")
public class OpenAiCompatController {

    private final RagChatService ragChatService;
    private final ObjectMapper json;
    private final String modelId;

    public OpenAiCompatController(RagChatService ragChatService, ObjectMapper json, TeamConfig config) {
        this.ragChatService = ragChatService;
        this.json = json;
        this.modelId = "xrag-" + config.team();
    }

    public record Message(String role, String content) {
    }

    public record ChatCompletionRequest(String model, List<Message> messages, Boolean stream) {
    }

    public record Model(String id, String object, long created, String owned_by) {
    }

    public record ModelList(String object, List<Model> data) {
    }

    public record Delta(String role, String content) {
    }

    public record ChunkChoice(int index, Delta delta, String finish_reason) {
    }

    public record Chunk(String id, String object, long created, String model, List<ChunkChoice> choices) {
    }

    public record Choice(int index, Message message, String finish_reason) {
    }

    public record ChatCompletion(String id, String object, long created, String model, List<Choice> choices) {
    }

    @GetMapping("/models")
    public ModelList models() {
        return new ModelList("list", List.of(new Model(modelId, "model", Instant.now().getEpochSecond(), "xrag")));
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<Flux<String>> chatCompletions(@RequestBody ChatCompletionRequest request) {
        String question = lastUserMessage(request);
        String id = "chatcmpl-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();

        if (Boolean.TRUE.equals(request.stream())) {
            Flux<String> events = Flux.concat(
                    Flux.just(chunk(id, created, new Delta("assistant", ""), null)),
                    ragChatService.answer(question, null)
                            .map(token -> chunk(id, created, new Delta(null, token), null)),
                    Flux.just(chunk(id, created, new Delta(null, null), "stop"), "[DONE]"));
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(events);
        }
        Flux<String> completion = ragChatService.answer(question, null)
                .collect(StringBuilder::new, StringBuilder::append)
                .map(answer -> json.writeValueAsString(new ChatCompletion(id, "chat.completion", created, modelId,
                        List.of(new Choice(0, new Message("assistant", answer.toString()), "stop")))))
                .flux();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(completion);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", Map.of("message", e.getMessage(), "type", "invalid_request_error")));
    }

    private String chunk(String id, long created, Delta delta, String finishReason) {
        return json.writeValueAsString(new Chunk(id, "chat.completion.chunk", created,
                modelId, List.of(new ChunkChoice(0, delta, finishReason))));
    }

    private static String lastUserMessage(ChatCompletionRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            throw new IllegalArgumentException("messages est requis");
        }
        return request.messages().reversed().stream()
                .filter(m -> "user".equals(m.role()) && m.content() != null && !m.content().isBlank())
                .findFirst()
                .map(Message::content)
                .orElseThrow(() -> new IllegalArgumentException("aucun message user dans la requête"));
    }
}
