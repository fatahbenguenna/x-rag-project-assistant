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
    public int purgeUnreferencedTopics() {
        // Un TOPIC n'est jamais atteint par purgeOrphanNodes (arête vers le projet + alias
        // créés systématiquement) : on le purge dès qu'aucun chunk ne le référence. Alias
        // et arêtes d'abord (FK graph_edges -> graph_nodes).
        jdbc.update("""
                DELETE FROM entity_aliases a WHERE a.node_id IN (
                    SELECT n.id FROM graph_nodes n WHERE n.type = 'TOPIC'
                      AND NOT EXISTS (SELECT 1 FROM rag_chunks c WHERE c.node_ids @> ARRAY[n.id]))
                """);
        jdbc.update("""
                DELETE FROM graph_edges e WHERE e.src IN (
                    SELECT n.id FROM graph_nodes n WHERE n.type = 'TOPIC'
                      AND NOT EXISTS (SELECT 1 FROM rag_chunks c WHERE c.node_ids @> ARRAY[n.id]))
                   OR e.dst IN (
                    SELECT n.id FROM graph_nodes n WHERE n.type = 'TOPIC'
                      AND NOT EXISTS (SELECT 1 FROM rag_chunks c WHERE c.node_ids @> ARRAY[n.id]))
                """);
        return jdbc.update("""
                DELETE FROM graph_nodes n WHERE n.type = 'TOPIC'
                  AND NOT EXISTS (SELECT 1 FROM rag_chunks c WHERE c.node_ids @> ARRAY[n.id])
                """);
    }

    @Override
    public int dehubTopicEdges() {
        // (a) l'étoile TOPIC -> PROJECT disparaît…
        jdbc.update("""
                DELETE FROM graph_edges e USING graph_nodes n
                WHERE e.src = n.id AND n.type = 'TOPIC' AND e.dst LIKE 'project:%'
                """);
        // (b) …remplacée par TOPIC -> document (page/issue/class), dérivée des node_ids des
        // chunks (le doc et ses topics cohabitent dans node_ids). EXISTS : jamais d'arête
        // vers un nœud fantôme (FK).
        return jdbc.update("""
                INSERT INTO graph_edges (src, dst, type)
                SELECT DISTINCT t.nid, d.nid, 'REFERENCES'
                FROM rag_chunks c
                CROSS JOIN LATERAL unnest(c.node_ids) t(nid)
                CROSS JOIN LATERAL unnest(c.node_ids) d(nid)
                WHERE t.nid LIKE 'topic:%' AND d.nid NOT LIKE 'topic:%' AND d.nid NOT LIKE 'project:%'
                  AND EXISTS (SELECT 1 FROM graph_nodes gn WHERE gn.id = d.nid)
                ON CONFLICT DO NOTHING
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
