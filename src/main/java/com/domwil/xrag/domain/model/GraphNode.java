package com.domwil.xrag.domain.model;

import java.util.Map;

/** Nœud du graphe de connaissances (table graph_nodes). */
public record GraphNode(String id, String type, String name, Map<String, Object> props) {

    public GraphNode {
        props = props == null ? Map.of() : Map.copyOf(props);
    }

    public static GraphNode of(String id, String type, String name) {
        return new GraphNode(id, type, name, Map.of());
    }

    /** Types de nœuds du modèle (voir CLAUDE.md). */
    public static final class Types {
        public static final String PROJECT = "PROJECT";
        public static final String PAGE = "PAGE";
        public static final String MR = "MR";
        public static final String ISSUE = "ISSUE";
        public static final String CLASS = "CLASS";
        public static final String TABLE = "TABLE";
        public static final String TOPIC = "TOPIC";
        public static final String ENDPOINT = "ENDPOINT";

        private Types() {
        }
    }
}
