package com.domwil.xrag.domain.port;

import com.domwil.xrag.domain.model.Chunk;
import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.model.UnattachedDocument;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Port des chunks vectorisés (table rag_chunks) : recherche + ingestion upsert only. */
public interface ChunkRepository {

    void upsert(Collection<Chunk> chunks);

    /**
     * Documents dont les chunks ne sont rattachés à aucun nœud (node_ids vide), les plus
     * gros d'abord (impact maximal sur le ratio de rattachement). Alimente l'enrichissement
     * LLM nocturne du graphe.
     */
    List<UnattachedDocument> unattachedDocuments(int limit);

    /**
     * Documents sans aucun nœud {@code TOPIC} (rattachés éventuellement à leur PAGE/ISSUE/CLASS,
     * mais pas à un sujet), optionnellement filtrés par source. Sert à densifier la couverture
     * sémantique de sources déjà rattachées (ex. Confluence/Jira), au-delà du seul rattachement.
     *
     * @param sources sources à cibler (ex. {@code [confluence, jira]}) ; vide = toutes
     */
    List<UnattachedDocument> documentsNeedingTopics(Collection<String> sources, int limit);

    /**
     * Ajoute les nœuds donnés aux chunks d'un document (colonne node_ids), en <b>fusion</b> :
     * les rattachements existants (PAGE/ISSUE/CLASS) sont préservés. Upsert only, sans ré-embedding.
     *
     * @return nombre de chunks mis à jour
     */
    int attachToNodes(String source, String path, Set<String> nodeIds);

    /** Version indexée d'un document ({@code null} si jamais indexé) — évite le re-embedding inutile. */
    Optional<String> indexedVersion(String source, String path);

    /** Purge les chunks du document absents de {@code keepIds} (document raccourci). */
    void deleteOtherChunksOf(String source, String path, Collection<String> keepIds);

    /**
     * Recherche hybride : similarité vectorielle + full-text (tsvector français),
     * boostée pour les chunks rattachés aux nœuds du sous-graphe.
     *
     * @param embedding    embedding de la question (bge-m3, 1024 dims)
     * @param query        question en texte libre (full-text)
     * @param boostNodeIds nœuds du sous-graphe (boost node_ids && ...)
     * @param project      filtre métadonnées par projet ({@code null} = tous)
     */
    List<ScoredChunk> hybridSearch(float[] embedding, String query, Set<String> boostNodeIds,
                                   String project, int limit);
}
