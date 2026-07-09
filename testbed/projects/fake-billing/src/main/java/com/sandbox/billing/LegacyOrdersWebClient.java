package com.sandbox.billing;

import org.springframework.web.reactive.function.client.WebClient;

/**
 * PIÈGE VOLONTAIRE (scenario.md §2) : appel HTTP via WebClient, hors Feign —
 * l'extracteur Java ne doit produire AUCUNE arête CALLS_API ici.
 * Ce fichier est aussi la cible de l'étape C (suppression -> purge des orphelins).
 */
public class LegacyOrdersWebClient {

    private final WebClient webClient = WebClient.create("http://fake-orders:8080");

    public String fetchOrder(Long id) {
        return webClient.get().uri("/api/orders/{id}", id)
            .retrieve().bodyToMono(String.class).block();
    }
}
