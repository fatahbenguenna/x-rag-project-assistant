package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.GraphQualityMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphQualityServiceTest {

    @Test
    void graphesSainSansTrou() {
        var report = evaluate(new GraphQualityMetrics(100, 250, 5, 1000, 800, List.of()));

        assertThat(report.hasGaps()).isFalse();
        assertThat(report.verdict()).contains("l'extraction déterministe suffit");
    }

    @Test
    void chunksNonRattachesDetectes() {
        var report = evaluate(new GraphQualityMetrics(100, 250, 5, 1000, 200, List.of()));

        assertThat(report.hasGaps()).isTrue();
        assertThat(report.verdict()).contains("extraction LLM").contains("20% des chunks");
    }

    @Test
    void nœudsOrphelinsDetectes() {
        var report = evaluate(new GraphQualityMetrics(100, 250, 40, 1000, 900, List.of()));

        assertThat(report.gaps()).anySatisfy(g -> assertThat(g).contains("orphelins").contains("alias"));
    }

    @Test
    void projetsSansRelationStructuranteDetectes() {
        var report = evaluate(new GraphQualityMetrics(100, 250, 5, 1000, 900, List.of("fps-suite", "fps-pos")));

        assertThat(report.gaps()).anySatisfy(g -> assertThat(g).contains("fps-suite, fps-pos"));
    }

    @Test
    void indexVideSansFauxPositif() {
        var report = evaluate(new GraphQualityMetrics(0, 0, 0, 0, 0, List.of()));

        assertThat(report.hasGaps()).isFalse();
    }

    private static GraphQualityService.Report evaluate(GraphQualityMetrics metrics) {
        return new GraphQualityService(() -> metrics).evaluate();
    }
}
