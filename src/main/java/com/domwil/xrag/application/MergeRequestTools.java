package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.MergeRequestMeta;
import com.domwil.xrag.domain.port.MergeRequestRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tools (function calling) pour les questions factuelles/structurées sur les
 * MRs — tris, comptages, "quelle MR ouverte est la plus vieille ?". Le RAG
 * seul répond mal à ces questions : ici, c'est du SQL.
 */
public class MergeRequestTools {

    /** Mots vides français écartés de la recherche par sujet (filet de sécurité si le LLM passe une phrase). */
    private static final Set<String> STOPWORDS = Set.of(
            "le", "la", "les", "un", "une", "des", "de", "du", "et", "ou", "au", "aux", "en",
            "dans", "par", "pour", "sur", "avec", "sans", "entre", "vers", "est", "sont", "ont",
            "qui", "que", "quoi", "dont", "ce", "cet", "cette", "ces", "se", "sa", "son", "ses",
            "leur", "leurs", "il", "elle", "ils", "elles", "on", "ne", "pas", "plus", "moi", "toi",
            "comment", "quel", "quelle", "quelles", "quels", "tout", "toute", "tous", "toutes",
            "relation", "liste");

    private static final int MAX_CONCEPTS = 8;

    private final MergeRequestRepository mergeRequests;

    /** Mot (minuscule) → formes du concept (le terme + ses synonymes), pour l'expansion métier↔code. */
    private final Map<String, List<String>> conceptByWord;

    public MergeRequestTools(MergeRequestRepository mergeRequests, Map<String, List<String>> synonyms) {
        this.mergeRequests = mergeRequests;
        this.conceptByWord = buildConceptIndex(synonyms);
    }

    @Tool(description = """
            Liste les merge requests GitLab. À utiliser pour toute question factuelle
            sur les MRs (les plus vieilles, les plus récentes, par état). Retourne
            titre, état, auteur, projet, dates et URL.""")
    public String listMergeRequests(
            @ToolParam(description = "État : opened, merged, closed ou all") String state,
            @ToolParam(description = "Colonne de tri : created_at, updated_at ou merged_at") String sortBy,
            @ToolParam(description = "true = plus anciennes d'abord, false = plus récentes d'abord") boolean oldestFirst,
            @ToolParam(description = "Nombre maximum de résultats (10 par défaut)", required = false) Integer limit) {
        var results = mergeRequests.find(normalizeState(state), sortBy, oldestFirst,
                limit == null || limit < 1 ? 10 : Math.min(limit, 50));
        if (results.isEmpty()) {
            return "Aucune merge request ne correspond.";
        }
        return results.stream().map(MergeRequestTools::format).collect(Collectors.joining("\n"));
    }

    @Tool(description = """
            Recherche les merge requests GitLab par sujet / mot-clé (nom de projet, composant,
            fonctionnalité — ex. « KDS », « caisse », « multi-tenant », « onboarding »). À utiliser
            pour toute question du type « quelles MRs concernent X ? », « liste les PR liées à Y »,
            « y a-t-il eu des MRs sur Z ? ». Cherche dans le titre, la description, les labels et les
            branches ; retourne les MRs correspondantes, les plus pertinentes d'abord.""")
    public String searchMergeRequests(
            @ToolParam(description = "Termes distinctifs du sujet, séparés par des espaces (ex. « caisse KDS », "
                    + "« onboarding client »). Donner les mots-clés, pas une phrase complète.") String keywords,
            @ToolParam(description = "Nombre maximum de résultats (20 par défaut)", required = false) Integer limit) {
        var results = mergeRequests.search(toConcepts(keywords),
                limit == null || limit < 1 ? 20 : Math.min(limit, 50));
        if (results.isEmpty()) {
            return "Aucune merge request ne mentionne « " + keywords + " ».";
        }
        return results.stream().map(MergeRequestTools::format).collect(Collectors.joining("\n"));
    }

    /**
     * Traduit la requête en concepts : chaque mot distinctif devient un groupe de formes
     * équivalentes (le mot + ses synonymes métier↔code), dédupliqué, borné à {@link #MAX_CONCEPTS}.
     */
    List<List<String>> toConcepts(String keywords) {
        var concepts = new ArrayList<List<String>>();
        var seen = new HashSet<String>();
        for (String word : tokenize(keywords)) {
            List<String> forms = conceptByWord.getOrDefault(word, List.of(word));
            if (seen.add(String.join("|", forms))) {
                concepts.add(forms);
            }
        }
        return concepts;
    }

    /** Mots distinctifs d'un texte : minuscules, sans mots vides, dédupliqués, bornés. */
    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var words = new LinkedHashSet<String>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9à-ÿ]+")) {
            if (token.length() >= 2 && !STOPWORDS.contains(token)) {
                words.add(token);
            }
            if (words.size() >= MAX_CONCEPTS) {
                break;
            }
        }
        return List.copyOf(words);
    }

    /** Index d'expansion : chaque mot d'une forme pointe vers toutes les formes de son concept. */
    private static Map<String, List<String>> buildConceptIndex(Map<String, List<String>> synonyms) {
        if (synonyms == null || synonyms.isEmpty()) {
            return Map.of();
        }
        var index = new HashMap<String, List<String>>();
        synonyms.forEach((canonical, forms) -> {
            var group = new LinkedHashSet<String>();
            group.add(canonical.toLowerCase(Locale.ROOT));
            forms.forEach(form -> group.add(form.toLowerCase(Locale.ROOT)));
            List<String> conceptForms = List.copyOf(group);
            for (String form : conceptForms) {
                for (String word : tokenize(form)) {
                    index.putIfAbsent(word, conceptForms);
                }
            }
        });
        return Map.copyOf(index);
    }

    @Tool(description = "Compte les merge requests GitLab par état (opened, merged, closed ou all).")
    public String countMergeRequests(
            @ToolParam(description = "État : opened, merged, closed ou all") String state) {
        return mergeRequests.count(normalizeState(state)) + " merge request(s) « " + state + " »";
    }

    private static String normalizeState(String state) {
        return state == null || state.isBlank() ? "all" : state.trim().toLowerCase();
    }

    private static String format(MergeRequestMeta mr) {
        return "!%d [%s] %s — projet %s, par %s, créée le %s, maj le %s%s".formatted(
                mr.iid(), mr.state(), mr.title(), mr.project(),
                mr.author() == null ? "?" : mr.author(),
                mr.createdAt(), mr.updatedAt(),
                mr.webUrl() == null ? "" : " — " + mr.webUrl());
    }
}
