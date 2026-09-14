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

    /** Regex générique (majuscules strictes), comportement historique des extracteurs Confluence/Jira/MR. */
    public static Set<String> in(String text) {
        return collect(text, KEY, false);
    }

    /**
     * Variante pour les textes de COMMITS (mode dépôts git locaux) : bornée aux projets Jira
     * connus — détection INSENSIBLE à la casse ({@code pass-12} dans une branche ou un commit →
     * {@code PASS-12}), aucun faux positif. Sans projet connu : regex générique privée des
     * préfixes techniques fréquents dans les commits ({@code SHA-256}, {@code UTF-8}) —
     * la variante {@link #in(String)} des autres sources n'est pas affectée (un projet Jira
     * nommé {@code PCI} ou {@code ISO} y reste détecté).
     */
    public static Set<String> in(String text, Collection<String> projectKeys) {
        Pattern pattern = patternFor(projectKeys);
        return collect(text, pattern, pattern == KEY);
    }

    private static Set<String> collect(String text, Pattern pattern, boolean dropTechnicalPrefixes) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        var keys = new LinkedHashSet<String>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String key = matcher.group(1).toUpperCase(Locale.ROOT);
            if (dropTechnicalPrefixes && TECHNICAL_PREFIXES.contains(key.substring(0, key.indexOf('-')))) {
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
