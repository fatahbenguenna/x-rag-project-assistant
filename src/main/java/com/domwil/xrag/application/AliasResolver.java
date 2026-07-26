package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.GraphNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Résolution d'entités : toutes les façons de nommer un projet ("FPS KDS",
 * fps-kds, FPSKDS) pointent vers le même nœud canonique "project:fpskds".
 * Alimenté par team-config.yml (aliases). Critique : sans résolution, le
 * graphe se fragmente.
 */
public class AliasResolver {

    private final Map<String, String> normalizedToCanonical = new LinkedHashMap<>();
    private final Map<String, String> canonicalDisplay = new LinkedHashMap<>();
    private final Pattern mentionPattern;

    public AliasResolver(Map<String, List<String>> aliases) {
        var alternatives = new LinkedHashSet<String>();
        aliases.forEach((canonical, forms) -> {
            normalizedToCanonical.put(normalize(canonical), canonical);
            alternatives.add(Pattern.quote(canonical));
            canonicalDisplay.put(canonical, forms.isEmpty() ? canonical : forms.getFirst());
            for (String form : forms) {
                normalizedToCanonical.put(normalize(form), canonical);
                alternatives.add(Pattern.quote(form));
            }
        });
        mentionPattern = alternatives.isEmpty()
                ? null
                : Pattern.compile("(?i)(?<![\\p{L}\\p{N}])(" + String.join("|", alternatives) + ")(?![\\p{L}\\p{N}])");
    }

    /** Forme normalisée : minuscules, sans séparateurs ("fps-kds" -> "fpskds"). */
    public static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** Résout une mention vers l'id canonique de projet ("project:fpskds"). */
    public Optional<String> resolveProjectId(String mention) {
        return Optional.ofNullable(normalizedToCanonical.get(normalize(mention)))
                .map(AliasResolver::projectNodeId);
    }

    /**
     * Id de projet pour un slug technique (repo GitLab, clé Jira...) : alias si
     * connu, sinon le slug normalisé — le projet reste dans le graphe même sans
     * alias déclaré.
     */
    public String projectIdFor(String slug) {
        return resolveProjectId(slug).orElse(projectNodeId(normalize(slug)));
    }

    /** Ids canoniques des projets mentionnés dans un texte libre. */
    public Set<String> projectsMentionedIn(String text) {
        if (mentionPattern == null || text == null || text.isBlank()) {
            return Set.of();
        }
        var found = new LinkedHashSet<String>();
        Matcher matcher = mentionPattern.matcher(text);
        while (matcher.find()) {
            String canonical = normalizedToCanonical.get(normalize(matcher.group(1)));
            if (canonical != null) {
                found.add(projectNodeId(canonical));
            }
        }
        return found;
    }

    /** Nœud PROJECT canonique pour un id de nœud "project:xxx". */
    public GraphNode projectNode(String projectNodeId) {
        String canonical = projectNodeId.substring(projectNodeId.indexOf(':') + 1);
        return GraphNode.of(projectNodeId, GraphNode.Types.PROJECT,
                canonicalDisplay.getOrDefault(canonical, canonical));
    }

    /** Table entity_aliases : alias normalisé -> id de nœud canonique. */
    public Map<String, String> aliasTable() {
        var table = new LinkedHashMap<String, String>();
        normalizedToCanonical.forEach((normalized, canonical) ->
                table.put(normalized, projectNodeId(canonical)));
        return table;
    }

    /** Nœuds PROJECT canoniques déclarés dans la configuration. */
    public List<GraphNode> declaredProjectNodes() {
        return canonicalDisplay.keySet().stream()
                .map(canonical -> projectNode(projectNodeId(canonical)))
                .toList();
    }

    public static String projectNodeId(String canonical) {
        return "project:" + canonical;
    }
}
