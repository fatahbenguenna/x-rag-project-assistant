package com.domwil.xrag.adapter.out.persistence;

import com.domwil.xrag.domain.port.IndexingStatusRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecture des métriques d'indexation persistées pour le dashboard. Requêtes de
 * comptage légères (aucune destruction, aucun VACUUM), sûres à appeler en journée.
 */
@Repository
public class JdbcIndexingStatusRepository implements IndexingStatusRepository {

    private final JdbcTemplate jdbc;

    public JdbcIndexingStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PersistedMetrics metrics() {
        Map<String, Long> chunksBySource = new LinkedHashMap<>();
        jdbc.query("SELECT source, count(*) AS n FROM rag_chunks GROUP BY source ORDER BY source",
                rs -> {
                    chunksBySource.put(rs.getString("source"), rs.getLong("n"));
                });
        long totalChunks = chunksBySource.values().stream().mapToLong(Long::longValue).sum();

        List<SourceSyncState> sources = jdbc.query(
                "SELECT source, last_sync, last_status FROM sync_state ORDER BY source",
                (rs, i) -> {
                    Timestamp lastSync = rs.getTimestamp("last_sync");
                    return new SourceSyncState(
                            rs.getString("source"),
                            lastSync == null ? null : lastSync.toInstant(),
                            rs.getString("last_status"));
                });

        return new PersistedMetrics(
                totalChunks, chunksBySource,
                count("SELECT count(*) FROM graph_nodes"),
                count("SELECT count(*) FROM graph_edges"),
                count("SELECT count(*) FROM merge_requests"),
                sources);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }
}
