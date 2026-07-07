package com.domwil.xrag.application;

import com.domwil.xrag.domain.model.GraphNode;
import com.domwil.xrag.domain.model.ScoredChunk;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.model.Subgraph;
import com.domwil.xrag.domain.port.ChunkRepository;
import com.domwil.xrag.domain.port.GraphSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fiches projet pré-calculées : pour chaque projet déclaré, une synthèse
 * structurée (stack, architecture, données, endpoints, events, dépendances du
 * graphe) générée par le LLM et indexée comme document premium. Indispensables
 * pour tenir les réponses "résumé projet" en moins de 20-25 s.
 */
public class ProjectSheetService {

    private static final Logger log = LoggerFactory.getLogger(ProjectSheetService.class);

    public static final String SOURCE = "project-sheet";

    private static final String SHEET_PROMPT = """
            À partir des relations du graphe et des extraits ci-dessous, rédige la fiche du \
            projet « {project} » avec exactement ces sections (rester factuel, ne rien inventer) :
            ## Rôle
            ## Stack technique
            ## Architecture et modèle de données
            ## API et endpoints
            ## Events publiés / consommés
            ## Dépendances et interactions
            Termine par les sources utilisées.

            Relations du graphe :
            {graph}

            Extraits :
            {documents}
            """;

    private final AliasResolver aliases;
    private final GraphSearchRepository graphSearch;
    private final ChunkRepository chunks;
    private final EmbeddingModel embeddingModel;
    private final ChatClient chatClient;
    private final IngestionService ingestion;

    public ProjectSheetService(AliasResolver aliases, GraphSearchRepository graphSearch,
                               ChunkRepository chunks, EmbeddingModel embeddingModel,
                               ChatClient chatClient, IngestionService ingestion) {
        this.aliases = aliases;
        this.graphSearch = graphSearch;
        this.chunks = chunks;
        this.embeddingModel = embeddingModel;
        this.chatClient = chatClient;
        this.ingestion = ingestion;
    }

    public void refreshAll() {
        for (GraphNode project : aliases.declaredProjectNodes()) {
            try {
                refresh(project);
            } catch (Exception e) {
                log.error("Fiche projet {} en échec", project.name(), e);
            }
        }
    }

    private void refresh(GraphNode project) {
        Subgraph subgraph = graphSearch.neighborhood(Set.of(project.id()), 2);
        String query = "architecture stack endpoints events du projet " + project.name();
        List<ScoredChunk> context = chunks.hybridSearch(
                embeddingModel.embed(query), query, subgraph.nodeIds(), null, 12);

        String sheet = chatClient.prompt()
                .user(user -> user.text(SHEET_PROMPT)
                        .param("project", project.name())
                        .param("graph", subgraph.isEmpty() ? "(aucune)" : GraphTextSerializer.serialize(subgraph))
                        .param("documents", context.stream()
                                .map(c -> "- " + c.citation() + " : " + excerpt(c.content()))
                                .collect(Collectors.joining("\n"))))
                .call()
                .content();

        String canonical = project.id().substring(project.id().indexOf(':') + 1);
        ingestion.ingest(new SourceDocument(
                        SOURCE, canonical, canonical,
                        "Fiche projet " + project.name(), sheet, null,
                        LocalDate.now().toString(), null,
                        java.util.Map.of("premium", true)),
                Set.of(project.id()));
        log.info("Fiche projet {} régénérée ({} caractères)", project.name(),
                sheet == null ? 0 : sheet.length());
    }

    private static String excerpt(String content) {
        return content.length() <= 800 ? content : content.substring(0, 800) + "…";
    }
}
