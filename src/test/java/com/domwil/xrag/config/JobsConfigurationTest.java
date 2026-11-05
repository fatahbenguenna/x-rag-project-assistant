package com.domwil.xrag.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobsConfigurationTest {

    @Test
    void prechauffeLeModelePrincipalEtLeFallbackAvecLeNumCtxDesRequetes() {
        // Valeur différente du défaut (8192) : un num_ctx codé en dur serait détecté.
        var llm = new TeamConfig.Llm("ollama", "qwen2.5:7b-instruct", "qwen2.5:3b", null, 16384, null);

        List<ChatOptions> warmUps = JobsConfiguration.chatWarmUpOptions(llm);

        assertThat(warmUps).extracting(ChatOptions::getModel)
                .containsExactly("qwen2.5:7b-instruct", "qwen2.5:3b");
        // Un num_ctx différent de celui des requêtes forcerait Ollama à recharger le modèle.
        assertThat(warmUps).allSatisfy(options -> {
            assertThat(((OllamaChatOptions) options).getNumCtx()).isEqualTo(16384);
            assertThat(((OllamaChatOptions) options).getNumPredict()).isEqualTo(1);
        });
    }

    @Test
    void sansFallbackPrechauffeLeSeulModelePrincipal() {
        var llm = new TeamConfig.Llm("ollama", "qwen2.5:7b-instruct", " ", null, null, null);

        assertThat(JobsConfiguration.chatWarmUpOptions(llm)).extracting(ChatOptions::getModel)
                .containsExactly("qwen2.5:7b-instruct");
    }

    @Test
    void aucunWarmUpDeChatSurUnProviderDistant() {
        var llm = new TeamConfig.Llm("gemini", "gemini-2.5-flash", "qwen2.5:3b", null, null, null);

        assertThat(JobsConfiguration.chatWarmUpOptions(llm)).isEmpty();
    }
}
