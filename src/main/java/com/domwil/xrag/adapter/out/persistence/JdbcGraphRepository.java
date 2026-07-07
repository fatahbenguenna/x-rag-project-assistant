package com.domwil.xrag.adapter.out.persistence;

import com.domwil.xrag.domain.model.GraphEdge;
import com.domwil.xrag.domain.model.GraphNode;
import com.domwil.xrag.domain.port.GraphRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;

/** Adapter JDBC du graphe : upsert only, jamais de destruction d'index. */
@Repository
public class JdbcGraphRepository implements GraphRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcGraphRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void upsertNodes(Collection<GraphNode> nodes) {
        jdbc.batchUpdate("""
                        INSERT INTO graph_nodes (id, type, name, props)
                        VALUES (?, ?, ?, ?::jsonb)
                        ON CONFLICT (id) DO UPDATE
                          SET type = EXCLUDED.type,
                              name = EXCLUDED.name,
                              props = graph_nodes.props || EXCLUDED.props
                        """,
                nodes.stream()
                        .map(n -> new Object[]{n.id(), n.type(), n.name(), toJson(n.props())})
                        .toList());
    }

    @Override
    public void upsertEdges(Collection<GraphEdge> edges) {
        jdbc.batchUpdate("""
                        INSERT INTO graph_edges (src, dst, type, props)
                        VALUES (?, ?, ?, ?::jsonb)
                        ON CONFLICT (src, dst, type) DO UPDATE
                          SET props = graph_edges.props || EXCLUDED.props
                        """,
                edges.stream()
                        .map(e -> new Object[]{e.src(), e.dst(), e.type(), toJson(e.props())})
                        .toList());
    }

    @Override
    public void upsertAliases(Map<String, String> aliasToNodeId) {
        jdbc.batchUpdate("""
                        INSERT INTO entity_aliases (alias, node_id)
                        VALUES (?, ?)
                        ON CONFLICT (alias) DO UPDATE SET node_id = EXCLUDED.node_id
                        """,
                aliasToNodeId.entrySet().stream()
                        .map(e -> new Object[]{e.getKey(), e.getValue()})
                        .toList());
    }

    private String toJson(Map<String, Object> props) {
        try {
            return json.writeValueAsString(props);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("props non sérialisables en JSON", e);
        }
    }
}
