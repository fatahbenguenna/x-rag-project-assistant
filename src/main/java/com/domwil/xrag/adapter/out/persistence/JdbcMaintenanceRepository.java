package com.domwil.xrag.adapter.out.persistence;

import com.domwil.xrag.domain.port.MaintenanceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class JdbcMaintenanceRepository implements MaintenanceRepository {

    private final JdbcTemplate jdbc;

    public JdbcMaintenanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int purgeOrphanNodes() {
        return jdbc.update("""
                DELETE FROM graph_nodes n
                WHERE NOT EXISTS (SELECT 1 FROM graph_edges e WHERE e.src = n.id OR e.dst = n.id)
                  AND NOT EXISTS (SELECT 1 FROM rag_chunks c WHERE c.node_ids @> ARRAY[n.id])
                  AND NOT EXISTS (SELECT 1 FROM entity_aliases a WHERE a.node_id = n.id)
                """);
    }

    @Override
    public void vacuumAnalyze() {
        // Hors transaction (autocommit JdbcTemplate). Index HNSW conservé, jamais reconstruit.
        jdbc.execute("VACUUM ANALYZE rag_chunks");
        jdbc.execute("VACUUM ANALYZE graph_nodes");
        jdbc.execute("VACUUM ANALYZE graph_edges");
        jdbc.execute("VACUUM ANALYZE merge_requests");
    }

    @Override
    public Map<String, Object> stats() {
        var stats = new LinkedHashMap<String, Object>();
        jdbc.query("SELECT source, count(*) AS n FROM rag_chunks GROUP BY source ORDER BY source",
                rs -> {
                    stats.put("chunks." + rs.getString("source"), rs.getLong("n"));
                });
        stats.put("graph.nodes", jdbc.queryForObject("SELECT count(*) FROM graph_nodes", Long.class));
        stats.put("graph.edges", jdbc.queryForObject("SELECT count(*) FROM graph_edges", Long.class));
        stats.put("mergeRequests", jdbc.queryForObject("SELECT count(*) FROM merge_requests", Long.class));
        return stats;
    }
}
