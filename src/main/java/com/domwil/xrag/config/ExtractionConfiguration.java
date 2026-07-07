package com.domwil.xrag.config;

import com.domwil.xrag.application.AliasResolver;
import com.domwil.xrag.domain.port.GraphRepository;
import com.domwil.xrag.domain.port.RelationExtractor;
import com.domwil.xrag.extraction.ConfluenceRelationExtractor;
import com.domwil.xrag.extraction.JavaRelationExtractor;
import com.domwil.xrag.extraction.JiraRelationExtractor;
import com.domwil.xrag.extraction.MergeRequestGraphMapper;
import com.domwil.xrag.extraction.TypeScriptRelationExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** Active les plugins d'extraction déclarés dans team-config.yml (extractors.*). */
@Configuration
public class ExtractionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExtractionConfiguration.class);

    @Bean
    public AliasResolver aliasResolver(TeamConfig config) {
        return new AliasResolver(config.aliases());
    }

    @Bean
    public List<RelationExtractor> relationExtractors(TeamConfig config, AliasResolver aliases) {
        var extractors = new ArrayList<RelationExtractor>();
        extractors.add(new ConfluenceRelationExtractor(aliases));
        extractors.add(new JiraRelationExtractor(aliases));
        if (config.extractors().java()) {
            extractors.add(new JavaRelationExtractor(aliases));
        }
        if (config.extractors().typescript()) {
            extractors.add(new TypeScriptRelationExtractor(aliases));
        }
        log.info("Extracteurs actifs : {}",
                extractors.stream().map(e -> e.getClass().getSimpleName()).toList());
        return List.copyOf(extractors);
    }

    @Bean
    public MergeRequestGraphMapper mergeRequestGraphMapper(AliasResolver aliases) {
        return new MergeRequestGraphMapper(aliases);
    }

    /**
     * Amorce le graphe au démarrage : nœuds PROJECT canoniques déclarés dans la
     * config + table entity_aliases. Idempotent (upsert only).
     */
    @Bean
    public ApplicationRunner aliasBootstrap(AliasResolver aliases, GraphRepository graph) {
        return args -> {
            graph.upsertNodes(aliases.declaredProjectNodes());
            graph.upsertAliases(aliases.aliasTable());
            log.info("Alias amorcés : {} entrées, {} projets canoniques",
                    aliases.aliasTable().size(), aliases.declaredProjectNodes().size());
        };
    }
}
