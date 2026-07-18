package com.domwil.xrag.adapter.out.persistence;

import com.domwil.xrag.domain.model.Chunk;
import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.model.UnattachedDocument;
import com.domwil.xrag.domain.port.ChunkRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Recherche hybride sur rag_chunks : candidats par similarité vectorielle
 * (index HNSW), full-text français et rattachement au sous-graphe, puis
 * re-scoring combiné. Poids : vecteur 0.6, full-text 0.25, graphe 0.3.
 */
@Repository
public class JdbcChunkRepository implements ChunkRepository {

    private static final int CANDIDATES_PER_CHANNEL = 40;

    private static final RowMapper<UnattachedDocument> UNATTACHED_MAPPER = (rs, i) -> new UnattachedDocument(
            rs.getString("source"), rs.getString("project"),
            rs.getString("path"), rs.getString("title"), rs.getString("text"));

    private final JdbcTemplate jdbc;

    public JdbcChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(Collection<Chunk> chunks) {
        jdbc.batchUpdate("""
                        INSERT INTO rag_chunks (id, source, project, path, chunk_index, title, content,
                                                url, node_ids, embedding, indexed_version, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?::vector, ?, now())
                        ON CONFLICT (id) DO UPDATE SET
                            project = EXCLUDED.project, title = EXCLUDED.title,
                            content = EXCLUDED.content, url = EXCLUDED.url,
                            node_ids = EXCLUDED.node_ids, embedding = EXCLUDED.embedding,
                            indexed_version = EXCLUDED.indexed_version, updated_at = now()
                        """,
                chunks.stream().map(c -> new Object[]{
                        c.id(), c.source(), c.project(), c.path(), c.chunkIndex(), c.title(),
                        c.content(), c.url(), PgArrays.textArray(c.nodeIds()),
                        PgArrays.vector(c.embedding()), c.indexedVersion()
                }).toList());
    }

    @Override
    public Optional<String> indexedVersion(String source, String path) {
        List<String> versions = jdbc.queryForList(
                "SELECT indexed_version FROM rag_chunks WHERE source = ? AND path = ? LIMIT 1",
                String.class, source, path);
        return versions.isEmpty() ? Optional.empty() : Optional.ofNullable(versions.getFirst());
    }

    @Override
    public void deleteOtherChunksOf(String source, String path, Collection<String> keepIds) {
        jdbc.update("DELETE FROM rag_chunks WHERE source = ? AND path = ? AND NOT (id = ANY(?::text[]))",
                source, path, PgArrays.textArray(keepIds));
    }

    @Override
    public List<UnattachedDocument> unattachedDocuments(int limit) {
        // Un document = un (source, path) ; on agrège son texte (tronqué) pour l'extraction LLM.
        // Les plus gros d'abord : rattacher un doc à N chunks fait gagner N au ratio.
        return jdbc.query("""
                        SELECT source, min(project) AS project, path, min(title) AS title,
                               left(string_agg(content, E'\\n' ORDER BY chunk_index), 3000) AS text
                        FROM rag_chunks
                        WHERE cardinality(node_ids) = 0
                        GROUP BY source, path
                        ORDER BY count(*) DESC
                        LIMIT ?
                        """,
                UNATTACHED_MAPPER, limit);
    }

    @Override
    public List<UnattachedDocument> documentsNeedingTopics(Collection<String> sources, int limit) {
        boolean allSources = sources == null || sources.isEmpty();
        // Concaténation (pas de String.formatted) : le SQL contient « LIKE 'topic:%' » et le
        // « %' » serait pris pour un spécificateur de format.
        String sql = "SELECT source, min(project) AS project, path, min(title) AS title, "
                + "left(string_agg(content, E'\\n' ORDER BY chunk_index), 3000) AS text "
                + "FROM rag_chunks "
                + "WHERE NOT EXISTS (SELECT 1 FROM unnest(node_ids) nid WHERE nid LIKE 'topic:%') "
                + (allSources ? "" : "AND source = ANY(?::text[]) ")
                + "GROUP BY source, path ORDER BY count(*) DESC LIMIT ?";
        Object[] params = allSources
                ? new Object[]{limit}
                : new Object[]{PgArrays.textArray(sources), limit};
        return jdbc.query(sql, UNATTACHED_MAPPER, params);
    }

    @Override
    public int attachToNodes(String source, String path, Set<String> nodeIds) {
        // Fusion : on préserve les rattachements existants (PAGE/ISSUE/CLASS) et on ajoute les
        // nœuds donnés, dédupliqués. array_agg peut renvoyer NULL sur un tableau vide -> coalesce.
        return jdbc.update("""
                        UPDATE rag_chunks
                        SET node_ids = coalesce(
                                (SELECT array_agg(DISTINCT nid) FROM unnest(node_ids || ?::text[]) nid),
                                '{}'::text[]),
                            updated_at = now()
                        WHERE source = ? AND path = ?
                        """,
                PgArrays.textArray(nodeIds), source, path);
    }

    @Override
    public List<ScoredChunk> hybridSearch(float[] embedding, String query, Set<String> boostNodeIds,
                                          String project, int limit) {
        String vector = PgArrays.vector(embedding);
        String nodeIds = PgArrays.textArray(boostNodeIds);
        return jdbc.query("""
                        WITH candidates AS (
                            (SELECT id FROM rag_chunks
                             WHERE embedding IS NOT NULL AND (?::text IS NULL OR project = ?)
                             ORDER BY embedding <=> ?::vector LIMIT ?)
                            UNION
                            (SELECT id FROM rag_chunks
                             WHERE tsv @@ plainto_tsquery('french', ?) AND (?::text IS NULL OR project = ?)
                             LIMIT ?)
                            UNION
                            (SELECT id FROM rag_chunks
                             WHERE node_ids && ?::text[] AND (?::text IS NULL OR project = ?)
                             LIMIT ?)
                        )
                        SELECT c.id, c.source, c.project, c.path, c.title, c.content, c.url,
                               (1 - (c.embedding <=> ?::vector)) * 0.6
                               + LEAST(COALESCE(ts_rank(c.tsv, plainto_tsquery('french', ?)), 0), 1.0) * 0.25
                               + (CASE WHEN c.node_ids && ?::text[] THEN 0.3 ELSE 0 END) AS score
                        FROM rag_chunks c
                        JOIN candidates ON candidates.id = c.id
                        WHERE c.embedding IS NOT NULL
                        ORDER BY score DESC
                        LIMIT ?
                        """,
                (rs, i) -> new ScoredChunk(
                        rs.getString("id"), rs.getString("source"), rs.getString("project"),
                        rs.getString("path"), rs.getString("title"), rs.getString("content"),
                        rs.getString("url"), rs.getDouble("score")),
                project, project, vector, CANDIDATES_PER_CHANNEL,
                query, project, project, CANDIDATES_PER_CHANNEL,
                nodeIds, project, project, CANDIDATES_PER_CHANNEL,
                vector, query, nodeIds, limit);
    }
}
