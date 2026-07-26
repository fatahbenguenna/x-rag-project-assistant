package com.domwil.xrag.application;

import com.domwil.xrag.domain.port.GraphSearchRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Détection d'entités dans une question : n-grammes (1 à 3 mots) normalisés,
 * résolus contre la table entity_aliases ("FPS KDS" -> project:fpskds).
 */
public class EntityDetector {

    private static final int MAX_NGRAM = 3;

    private final GraphSearchRepository graphSearch;

    public EntityDetector(GraphSearchRepository graphSearch) {
        this.graphSearch = graphSearch;
    }

    public Set<String> detectNodeIds(String question) {
        return graphSearch.resolveAliases(candidateTerms(question));
    }

    /** N-grammes normalisés de la question, candidats à la résolution d'alias. */
    static Set<String> candidateTerms(String question) {
        if (question == null || question.isBlank()) {
            return Set.of();
        }
        List<String> words = List.of(question.split("[^\\p{L}\\p{N}]+"));
        var terms = new LinkedHashSet<String>();
        for (int size = 1; size <= MAX_NGRAM; size++) {
            for (int start = 0; start + size <= words.size(); start++) {
                var ngram = new ArrayList<String>();
                for (int k = start; k < start + size; k++) {
                    ngram.add(words.get(k));
                }
                String normalized = AliasResolver.normalize(String.join("", ngram));
                if (!normalized.isEmpty()) {
                    terms.add(normalized);
                }
            }
        }
        return terms;
    }
}
