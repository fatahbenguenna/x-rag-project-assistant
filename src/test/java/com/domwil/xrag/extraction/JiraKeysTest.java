package com.domwil.xrag.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JiraKeysTest {

    @Test
    void regexGeneriqueHistoriqueInchangeePourLesAutresSources() {
        // un projet Jira nommé PCI/ISO reste détecté par la variante historique (Confluence, Jira, mapper MR)
        assertThat(JiraKeys.in("PASS-12 corrige PCI-4 et ISO-9, voir INFRA-3"))
                .containsExactly("PASS-12", "PCI-4", "ISO-9", "INFRA-3");
        assertThat(JiraKeys.in("pass-12 en minuscules")).isEmpty();
        assertThat(JiraKeys.in(null)).isEmpty();
    }

    @Test
    void varianteCommitsSansProjetConnuEcarteLesPrefixesTechniques() {
        assertThat(JiraKeys.in("PASS-12 corrige SHA-256 et UTF-8, voir INFRA-3", List.of()))
                .containsExactly("PASS-12", "INFRA-3");
    }

    @Test
    void borneeAuxProjetsConnusInsensibleALaCasseEtNormalisee() {
        assertThat(JiraKeys.in("feature/pass-42-login puis PASS-43 et RELEASE-2024", List.of("PASS")))
                .containsExactly("PASS-42", "PASS-43");
        assertThat(JiraKeys.in("PASS-1", List.of("INFRA"))).isEmpty();
        assertThat(JiraKeys.in("PASS-1 SHA-256 PCI-2", List.of("PCI"))).containsExactly("PCI-2"); // borné : PCI est un projet
    }
}
