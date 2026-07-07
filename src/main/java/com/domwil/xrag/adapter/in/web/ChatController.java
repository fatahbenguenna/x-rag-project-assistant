package com.domwil.xrag.adapter.in.web;

import com.domwil.xrag.application.RagChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Endpoint de chat streamé (SSE) — le streaming est obligatoire : premier
 * token ~2-5 s en CPU, réponse complète potentiellement > 30 s.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final RagChatService ragChatService;

    public ChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    public record ChatRequest(@NotBlank String question, String project) {
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody @Valid ChatRequest request) {
        return ragChatService.answer(request.question(), request.project());
    }
}
