package com.domwil.xrag.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntityDetectorTest {

    @Test
    void generatesNormalizedNgramsUpToThreeWords() {
        var terms = EntityDetector.candidateTerms("Comment faire communiquer Easy Loc et Epsilon ?");

        assertThat(terms)
                .contains("easy", "loc", "easyloc", "epsilon", "communiquereasyloc")
                .doesNotContain("commentfairecommuniquereasy"); // > 3 mots
    }

    @Test
    void toleratesBlankQuestions() {
        assertThat(EntityDetector.candidateTerms("  ")).isEmpty();
        assertThat(EntityDetector.candidateTerms(null)).isEmpty();
    }
}
