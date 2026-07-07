package com.domwil.xrag.adapter.out.persistence;

import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.GraphNode;
import com.domwil.xrag.domain.model.Subgraph;
import com.domwil.xrag.domain.port.GraphSearchRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lecture du graphe : résolution d'alias en SQL + voisinage WITH RECURSIVE (profondeur 2). */
@Repository
public class JdbcGraphSearchRepository implements GraphSearchRepository {

    /** Garde-fou : un hub très connecté ne doit pas exploser le prompt. */
    private static final int MAX_NODES = 120;

    private final JdbcTemplate jdbc;

    public JdbcGraphSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> resolveAliases(Collection<String> normalizedTerms) {
        if (normalizedTerms.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT node_id FROM entity_aliases WHERE alias = ANY(?::text[])",
                String.class, PgArrays.textArray(normalizedTerms)));
    }

    @Override
    public Subgraph neighborhood(Set<String> seedNodeIds, int depth) {
        if (seedNodeIds.isEmpty()) {
            return Subgraph.empty();
        }
        String seeds = PgArrays.textArray(seedNodeIds);
        List<String> nodeIds = jdbc.queryForList("""
                        WITH RECURSIVE walk(id, depth) AS (
                            SELECT unnest(?::text[]), 0
                            UNION
                            SELECT CASE WHEN e.src = w.id THEN e.dst ELSE e.src END, w.depth + 1
                            FROM graph_edges e
                            JOIN walk w ON e.src = w.id OR e.dst = w.id
                            WHERE w.depth < ?
                        )
                        SELECT DISTINCT id FROM walk LIMIT ?
                        """,
                String.class, seeds, depth, MAX_NODES);
        if (nodeIds.isEmpty()) {
            return Subgraph.empty();
        }

        String ids = PgArrays.textArray(nodeIds);
        List<GraphNode> nodes = jdbc.query(
                "SELECT id, type, name FROM graph_nodes WHERE id = ANY(?::text[])",
                (rs, i) -> GraphNode.of(rs.getString("id"), rs.getString("type"), rs.getString("name")),
                ids);
        List<GraphEdge> edges = jdbc.query(
                "SELECT src, dst, type FROM graph_edges WHERE src = ANY(?::text[]) AND dst = ANY(?::text[])",
                (rs, i) -> GraphEdge.of(rs.getString("src"), rs.getString("dst"), rs.getString("type")),
                ids, ids);
        return new Subgraph(nodes, edges);
    }
}
