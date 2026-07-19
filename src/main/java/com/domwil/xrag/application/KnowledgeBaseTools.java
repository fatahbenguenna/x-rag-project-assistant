package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.port.ChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Tool (function calling) de recherche plein-texte déterministe sur toute la base de
 * connaissances indexée (Confluence, code, MRs, Jira). Le LLM reformule la demande en
 * mots-clés et récupère des extraits sourcés. Complémentaire du retrieval hybride
 * pré-injecté : celui-ci est le socle garanti, le tool permet au LLM d'aller chercher une
 * source hors du top-K quand les extraits fournis ne suffisent pas.
 */
public class KnowledgeBaseTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTools.class);

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;
    private static final int EXCERPT_CHARS = 500;

    private final ChunkRepository chunks;

    public KnowledgeBaseTools(ChunkRepository chunks) {
        this.chunks = chunks;
    }

    @Tool(description = """
            Recherche plein-texte dans toute la base de connaissances indexée de l'équipe
            (documentation Confluence, code, merge requests, tickets Jira). À utiliser quand les
            extraits déjà fournis ne suffisent pas, ou pour retrouver une page, un fichier ou un
            ticket précis à partir de mots-clés (« où est documenté X ? », « y a-t-il un ticket sur
            Y ? »). Retourne les extraits les plus pertinents avec leur source.""")
    public String searchKnowledgeBase(
            @ToolParam(description = "Mots-clés distinctifs du sujet, séparés par des espaces "
                    + "(ex. « RoleAuthority sécurité rôles », « persistance bug alpha »). "
                    + "Donner des mots-clés, pas une phrase complète.") String keywords,
            @ToolParam(description = "Id de projet pour restreindre la recherche (optionnel)",
                    required = false) String project,
            @ToolParam(description = "Nombre maximum d'extraits (5 par défaut)",
                    required = false) Integer limit) {
        var results = chunks.keywordSearch(
                keywords,
                project == null || project.isBlank() ? null : project,
                limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT));
        log.info("Tool searchKnowledgeBase appelé (« {} ») : {} extrait(s)", keywords, results.size());
        if (results.isEmpty()) {
            return "Aucun document indexé ne mentionne « " + keywords + " ».";
        }
        var sb = new StringBuilder();
        for (ScoredChunk chunk : results) {
            String excerpt = chunk.content().length() > EXCERPT_CHARS
                    ? chunk.content().substring(0, EXCERPT_CHARS) + "…" : chunk.content();
            sb.append(chunk.citation());
            if (chunk.url() != null) {
                sb.append(" (").append(chunk.url()).append(")");
            }
            sb.append("\n").append(excerpt).append("\n\n");
        }
        return sb.toString().strip();
    }
}
