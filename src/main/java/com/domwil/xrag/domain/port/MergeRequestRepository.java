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
     * Recherche les MRs par sujet, sur titre + description + labels + branches.
     * Chaque {@code concept} est un groupe de formes équivalentes (le terme et ses
     * synonymes, ex. {@code [pos, caisse]}) : un concept compte pour un point si
     * l'une de ses formes apparaît. Match à frontière de mot (« pos » retrouve
     * {@code fps-pos} mais pas « compose »), pondéré titre &gt; corps. Les MRs qui
     * couvrent le plus de concepts distincts ressortent en premier. Alimente le tool
     * {@code searchMergeRequests}.
     *
     * @param concepts groupes de formes (déjà normalisés : minuscules, sans mots vides)
     */
    List<MergeRequestMeta> search(List<List<String>> concepts, int limit);

    /** Lookup déterministe d'une MR par son numéro (description COMPLÈTE, contrairement à search). */
    Optional<MergeRequestMeta> findByIid(long iid);

    long count(String state);

    /** Curseur pour la sync incrémentale (updated_after). */
    Optional<Instant> mostRecentUpdate();
}
