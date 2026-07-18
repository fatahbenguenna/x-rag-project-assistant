package com.domwil.xrag.adapter.out.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmTopicExtractorTest {

    @Test
    void extraitLesSujetsEnNettoyantPucesEtNumeros() {
        String response = """
                - Kafka
                2. WebSocket STOMP
                * multi-tenant""";

        assertThat(LlmTopicExtractor.parse(response))
                .containsExactly("kafka", "websocket stomp", "multi-tenant");
    }

    @Test
    void ignoreLesLignesDeCitationEtLeBruit() {
        // le system prompt RAG peut faire fuiter une ligne « Sources : … » — à écarter
        String response = """
                docker
                Sources : deploy.yml, README.md
                orchestration des conteneurs applicatifs du projet""";

        assertThat(LlmTopicExtractor.parse(response)).containsExactly("docker");
    }

    @Test
    void reponseAucunDonneListeVide() {
        assertThat(LlmTopicExtractor.parse("aucun")).isEmpty();
        assertThat(LlmTopicExtractor.parse("")).isEmpty();
        assertThat(LlmTopicExtractor.parse(null)).isEmpty();
    }

    @Test
    void deduplicationEtPlafondACinq() {
        String response = """
                cache
                cache
                redis
                postgres
                pgvector
                liquibase
                monitoring""";

        assertThat(LlmTopicExtractor.parse(response)).hasSize(5).doesNotHaveDuplicates();
    }
}
