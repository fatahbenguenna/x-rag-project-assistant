package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.MergeRequestMeta;
import com.domwil.xrag.domain.port.MergeRequestRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.stream.Collectors;

/**
 * Tools (function calling) pour les questions factuelles/structurées sur les
 * MRs — tris, comptages, "quelle MR ouverte est la plus vieille ?". Le RAG
 * seul répond mal à ces questions : ici, c'est du SQL.
 */
public class MergeRequestTools {

    private final MergeRequestRepository mergeRequests;

    public MergeRequestTools(MergeRequestRepository mergeRequests) {
        this.mergeRequests = mergeRequests;
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
