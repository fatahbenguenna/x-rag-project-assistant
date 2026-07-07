package com.domwil.xrag.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AliasResolverTest {

    private final AliasResolver resolver = new AliasResolver(Map.of(
            "easyloc", List.of("Easy Loc", "easy-loc", "EASYLOC"),
            "epsilon", List.of("Epsilon", "epsilon-service")));

    @Test
    void resolvesAllDeclaredFormsToTheSameCanonicalNode() {
        for (String form : List.of("Easy Loc", "easy-loc", "EASYLOC", "easyloc", "EasyLoc")) {
            assertThat(resolver.resolveProjectId(form)).hasValue("project:easyloc");
        }
    }

    @Test
    void fallsBackToNormalizedSlugForUnknownProjects() {
        assertThat(resolver.projectIdFor("mystery-svc")).isEqualTo("project:mysterysvc");
        assertThat(resolver.projectIdFor("epsilon-service")).isEqualTo("project:epsilon");
    }

    @Test
    void detectsMentionsInFreeText() {
        String text = "La comm entre Easy Loc et epsilon-service passe par Kafka. EASYLOC publie.";
        assertThat(resolver.projectsMentionedIn(text))
                .containsExactlyInAnyOrder("project:easyloc", "project:epsilon");
    }

    @Test
    void doesNotMatchInsideWords() {
        assertThat(resolver.projectsMentionedIn("epsilonesque")).isEmpty();
    }

    @Test
    void exposesAliasTableForPersistence() {
        assertThat(resolver.aliasTable())
                .containsEntry("easyloc", "project:easyloc")
                .containsEntry("epsilonservice", "project:epsilon");
    }
}
