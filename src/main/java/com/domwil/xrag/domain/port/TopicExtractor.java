package com.domwil.xrag.domain.port;

import java.util.List;

/**
 * Extraction LLM des sujets/entités d'un document, pour l'enrichissement nocturne du
 * graphe (décision d'architecture n°10 : le LLM ne complète le déterministe que si l'éval
 * montre des trous). Port : implémenté par un adapter ChatClient, mocké en test.
 */
public interface TopicExtractor {

    /** 1 à 5 sujets courts (composants, entités, concepts) ; liste vide si rien de pertinent. */
    List<String> extractTopics(String title, String text);
}
