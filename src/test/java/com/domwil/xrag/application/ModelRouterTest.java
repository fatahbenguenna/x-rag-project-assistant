package com.domwil.xrag.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRouterTest {

    private static final org.springframework.ai.chat.prompt.ChatOptions FALLBACK =
            org.springframework.ai.chat.prompt.ChatOptions.builder().model("qwen2.5:3b").build();

    private final ModelRouter router = new ModelRouter(FALLBACK);

    @Test
    void questionDescriptiveRouteeVersLeFallback() {
        assertThat(router.route("Explique-moi le projet Elog en 5 principes").getModel())
                .isEqualTo("qwen2.5:3b");
        assertThat(router.route("Résume la page d'architecture d'Easy Loc").getModel())
                .isEqualTo("qwen2.5:3b");
        assertThat(router.route("C'est quoi Epsilon ?").getModel())
                .isEqualTo("qwen2.5:3b");
    }

    @Test
    void questionFactuelleResteSurLeModelePrincipal() {
        assertThat(router.route("Quelle MR ouverte est la plus vieille ?")).isNull();
        assertThat(router.route("Combien de merge requests sont ouvertes ?")).isNull();
        assertThat(router.route("Liste les MRs ouvertes triées par date")).isNull();
    }

    @Test
    void syntheseTransProjetsResteSurLeModelePrincipal() {
        assertThat(router.route("Explique comment faire communiquer Easy Loc et Epsilon")).isNull();
        assertThat(router.route("Avons-nous eu un bug de persistance sur alpha ?")).isNull();
    }

    @Test
    void sansFallbackConfigureAucunRoutage() {
        assertThat(new ModelRouter(null).route("Explique-moi le projet Elog")).isNull();
        assertThat(new ModelRouter(null).route("Résume la page d'accueil")).isNull();
    }
}
