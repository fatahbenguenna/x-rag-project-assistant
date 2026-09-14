package com.domwil.xrag.adapter.out;

import java.util.Locale;
import java.util.Set;

/**
 * Règle commune aux transports du code (API GitLab, dépôts git locaux) : quels fichiers
 * d'un dépôt sont indexés — code + doc + config notable, par extension.
 */
public final class IndexedCodeFiles {

    private static final Set<String> INDEXED_EXTENSIONS = Set.of(
            "java", "kt", "ts", "html", "md", "adoc", "yml", "yaml", "properties", "sql", "xml", "json");

    private IndexedCodeFiles() {
    }

    public static boolean indexable(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 && INDEXED_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
