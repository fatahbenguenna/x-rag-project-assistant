package com.domwil.xrag.domain.port;

import com.domwil.xrag.domain.model.Subgraph;

import java.util.Collection;
import java.util.Set;

/** Port de lecture du graphe pour le retrieval. */
public interface GraphSearchRepository {

    /** Ids de nœuds canoniques correspondant aux termes normalisés (table entity_aliases). */
    Set<String> resolveAliases(Collection<String> normalizedTerms);

    /** Voisinage des nœuds graines jusqu'à {@code depth} sauts (WITH RECURSIVE). */
    Subgraph neighborhood(Set<String> seedNodeIds, int depth);
}
