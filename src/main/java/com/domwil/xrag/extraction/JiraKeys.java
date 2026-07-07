package com.domwil.xrag.extraction;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Détection des clés Jira dans un texte libre (regex du CLAUDE.md). */
public final class JiraKeys {

    private static final Pattern KEY = Pattern.compile("\\b([A-Z][A-Z0-9]+-\\d+)\\b");

    private JiraKeys() {
    }

    public static Set<String> in(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        var keys = new LinkedHashSet<String>();
        Matcher matcher = KEY.matcher(text);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }
}
