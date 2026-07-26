package com.domwil.xrag.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AliasResolverTest {

    private final AliasResolver resolver = new AliasResolver(Map.of(
            "fpskds", List.of("FPS KDS", "fps-kds", "FPSKDS"),
            "fpspos", List.of("fps-pos", "fps-pos-service")));

    @Test
    void resolvesAllDeclaredFormsToTheSameCanonicalNode() {
        for (String form : List.of("FPS KDS", "fps-kds", "FPSKDS", "fpskds", "FpsKds")) {
            assertThat(resolver.resolveProjectId(form)).hasValue("project:fpskds");
        }
    }

    @Test
    void fallsBackToNormalizedSlugForUnknownProjects() {
        assertThat(resolver.projectIdFor("mystery-svc")).isEqualTo("project:mysterysvc");
        assertThat(resolver.projectIdFor("fps-pos-service")).isEqualTo("project:fpspos");
    }

    @Test
    void detectsMentionsInFreeText() {
        String text = "La comm entre FPS KDS et fps-pos-service passe par Kafka. fpskds publie.";
        assertThat(resolver.projectsMentionedIn(text))
                .containsExactlyInAnyOrder("project:fpskds", "project:fpspos");
    }

    @Test
    void doesNotMatchInsideWords() {
        assertThat(resolver.projectsMentionedIn("fps-posesque")).isEmpty();
    }

    @Test
    void exposesAliasTableForPersistence() {
        assertThat(resolver.aliasTable())
                .containsEntry("fpskds", "project:fpskds")
                .containsEntry("fpsposservice", "project:fpspos");
    }
}
