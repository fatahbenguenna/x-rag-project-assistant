package com.domwil.xrag.domain.port;

import com.domwil.xrag.domain.model.MergeRequestMeta;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Port de la table de métadonnées MR (alimentée par la sync, lue par les tools). */
public interface MergeRequestRepository {

    void upsert(Collection<MergeRequestMeta> mergeRequests);

    /**
     * @param state      opened | merged | closed | locked | all
     * @param sortColumn created_at | updated_at | merged_at (liste blanche)
     */
    List<MergeRequestMeta> find(String state, String sortColumn, boolean ascending, int limit);

    /**
     * Recherche les MRs par sujet, sur titre + description + labels + branches. Les
     * termes du {@code query} sont matchés en OU ; les MRs qui en mentionnent le plus
     * ressortent en premier. Alimente le tool {@code searchMergeRequests} pour les
     * questions du type « quelles MRs concernent X ? ».
     */
    List<MergeRequestMeta> search(String query, int limit);

    long count(String state);

    /** Curseur pour la sync incrémentale (updated_after). */
    Optional<Instant> mostRecentUpdate();
}
