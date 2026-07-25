package com.domwil.xrag.adapter.out.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le SQL des repositories n'est pas testé unitairement (convention du repo : validation
 * live) — mais la construction de la tsquery OU est de la logique pure, testable.
 */
class JdbcChunkRepositoryTest {

    @Test
    void combineLesTokensEnOuAvecLexemesQuotes() {
        assertThat(JdbcChunkRepository.orTsQuery("retry connexion hardware"))
                .isEqualTo("'retry' | 'connexion' | 'hardware'");
    }

    @Test
    void normaliseMinusculesPonctuationEtDoublons() {
        assertThat(JdbcChunkRepository.orTsQuery("Multi-tenant : l'authentification multi-tenant ?"))
                .isEqualTo("'multi' | 'tenant' | 'authentification'");
    }

    @Test
    void ecarteLesTokensDUneLettreEtLimiteADouze() {
        assertThat(JdbcChunkRepository.orTsQuery("a b un mot")).isEqualTo("'un' | 'mot'");
        String longue = JdbcChunkRepository.orTsQuery(
                "t01 t02 t03 t04 t05 t06 t07 t08 t09 t10 t11 t12 t13 t14");
        assertThat(longue.split(" \\| ")).hasSize(12);
    }

    @Test
    void chaineVideOuBlanchePourNullifSql() {
        assertThat(JdbcChunkRepository.orTsQuery(null)).isEmpty();
        assertThat(JdbcChunkRepository.orTsQuery("  ")).isEmpty();
        assertThat(JdbcChunkRepository.orTsQuery("? ! …")).isEmpty();
    }
}
