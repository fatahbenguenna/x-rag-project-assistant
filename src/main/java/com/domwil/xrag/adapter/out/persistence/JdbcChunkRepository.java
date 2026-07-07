package com.domwil.xrag.adapter.out.persistence;

import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.port.ChunkRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

/**
 * Recherche hybride sur rag_chunks : candidats par similarité vectorielle
 * (index HNSW), full-text français et rattachement au sous-graphe, puis
 * re-scoring combiné. Poids : vecteur 0.6, full-text 0.25, graphe 0.3.
 */
@Repository
public class JdbcChunkRepository implements ChunkRepository {

    private static final int CANDIDATES_PER_CHANNEL = 40;

    private final JdbcTemplate jdbc;

    public JdbcChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
