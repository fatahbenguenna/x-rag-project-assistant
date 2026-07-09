package com.sandbox.orders;

import org.springframework.kafka.core.KafkaTemplate;

/**
 * PIÈGE VOLONTAIRE (scenario.md §2) : le nom du topic est construit dynamiquement.
 * L'extraction déterministe ne doit produire AUCUNE arête PUBLISHES ici —
 * c'est la mesure de sa limite, pas un bug du testbed.
 */
public class AuditTrailRouter {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public AuditTrailRouter(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void route(String region, String payload) {
        kafkaTemplate.send("audit-" + region, payload);
    }
}
