package com.domwil.xrag.extraction;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Détection des clés Jira dans un texte libre (regex du CLAUDE.md). */
public final class JiraKeys {

    private static final Pattern KEY = Pattern.compile("\\b([A-Z][A-Z0-9]+-\\d+)\\b");
    /** Préfixes techniques qui ressemblent à des clés Jira sans en être (SHA-256, UTF-8, ISO-8601…). */
    private static final Set<String> TECHNICAL_PREFIXES = Set.of(
            "SHA", "UTF", "ISO", "RFC", "HTTP", "TLS", "SSL", "CVE", "AES", "MD", "ECMA", "PCI", "IEEE", "X", "GPT");

    private JiraKeys() {
    }

    public static Set<String> in(String text) {
        return in(text, List.of());
    }

    /**
     * Variante bornée aux projets Jira connus : détection INSENSIBLE à la casse ({@code pass-12}
     * dans une branche ou un commit → {@code PASS-12}) et sans faux positifs ({@code SHA-256},
     * {@code UTF-8}). Liste vide = regex générique (majuscules strictes).
     */
    public static Set<String> in(String text, Collection<String> projectKeys) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        var keys = new LinkedHashSet<String>();
        Matcher matcher = patternFor(projectKeys).matcher(text);
        boolean generic = matcher.pattern() == KEY;
        while (matcher.find()) {
            String key = matcher.group(1).toUpperCase(Locale.ROOT);
            if (generic && TECHNICAL_PREFIXES.contains(key.substring(0, key.indexOf('-')))) {
                continue;
            }
            keys.add(key);
        }
        return keys;
    }

    private static Pattern patternFor(Collection<String> projectKeys) {
        if (projectKeys == null || projectKeys.isEmpty()) {
            return KEY;
        }
        String alternation = projectKeys.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(k -> Pattern.quote(k.strip()))
                .collect(Collectors.joining("|"));
        return alternation.isEmpty() ? KEY
                : Pattern.compile("(?i)\\b((?:" + alternation + ")-\\d+)\\b");
    }
}
