package com.domwil.xrag.adapter.out.persistence;

import java.util.Collection;
import java.util.stream.Collectors;

/** Littéraux de tableaux/vecteurs Postgres pour les paramètres castés (?::text[], ?::vector). */
final class PgArrays {

    private PgArrays() {
    }

    static String textArray(Collection<String> values) {
        return values.stream()
                .map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    static String vector(float[] embedding) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }
}
