package com.domwil.xrag.adapter.out.persistence;

import com.domwil.xrag.domain.model.GraphQualityMetrics;
import com.domwil.xrag.domain.port.GraphQualityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Mesures SQL de couverture du graphe sur l'index courant. */
@Repository
public class JdbcGraphQualityRepository implements GraphQualityRepository {

    private static final String STRUCTURAL_EDGE_TYPES =
            "('DEPENDS_ON', 'CALLS_API', 'SHARES_TABLE', 'PUBLISHES', 'CONSUMES')";

    private final JdbcTemplate jdbc;

    public JdbcGraphQualityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public GraphQualityMetrics measure() {
        long nodes = count("SELECT count(*) FROM graph_nodes");
        long edges = count("SELECT count(*) FROM graph_edges");
        long orphans = count("""
                SELECT count(*) FROM graph_nodes n
                WHERE NOT EXISTS (SELECT 1 FROM graph_edges e WHERE e.src = n.id OR e.dst = n.id)
                """);
        long chunks = count("SELECT count(*) FROM rag_chunks");
        long linked = count("SELECT count(*) FROM rag_chunks WHERE cardinality(node_ids) > 0");
        List<String> silentProjects = jdbc.queryForList("""
                SELECT n.name FROM graph_nodes n
                WHERE n.type = 'PROJECT'
                  AND NOT EXISTS (
                      SELECT 1 FROM graph_edges e
                      WHERE (e.src = n.id OR e.dst = n.id)
                        AND e.type IN %s
                  )
                ORDER BY n.name
                """.formatted(STRUCTURAL_EDGE_TYPES), String.class);

        return new GraphQualityMetrics(nodes, edges, orphans, chunks, linked, silentProjects);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
