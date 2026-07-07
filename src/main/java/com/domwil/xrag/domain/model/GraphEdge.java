package com.domwil.xrag.domain.model;

import java.util.Map;

/** Arête du graphe de connaissances (table graph_edges, PK (src, dst, type)). */
public record GraphEdge(String src, String dst, String type, Map<String, Object> props) {

    public GraphEdge {
        props = props == null ? Map.of() : Map.copyOf(props);
    }

    public static GraphEdge of(String src, String dst, String type) {
        return new GraphEdge(src, dst, type, Map.of());
    }

    /** Types d'arêtes du modèle (voir CLAUDE.md). */
    public static final class Types {
        public static final String DEPENDS_ON = "DEPENDS_ON";
        public static final String CALLS_API = "CALLS_API";
        public static final String SHARES_TABLE = "SHARES_TABLE";
        public static final String PUBLISHES = "PUBLISHES";
        public static final String CONSUMES = "CONSUMES";
        public static final String DOCUMENTS = "DOCUMENTS";
        public static final String MODIFIES = "MODIFIES";
        public static final String REFERENCES = "REFERENCES";
        public static final String LINKS_TO = "LINKS_TO";

        private Types() {
        }
    }
}
