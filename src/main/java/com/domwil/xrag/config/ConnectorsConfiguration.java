package com.domwil.xrag.config;

import com.domwil.xrag.adapter.out.confluence.ConfluenceConnector;
import com.domwil.xrag.adapter.out.gitlab.GitLabConnector;
import com.domwil.xrag.adapter.out.jira.JiraConnector;
import com.domwil.xrag.domain.port.ConnectorRegistry;
import com.domwil.xrag.domain.port.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Optional;

/**
 * Active les connecteurs déclarés dans team-config.yml. Les tokens viennent
 * de l'environnement (.env), jamais du YAML.
 */
@Configuration
public class ConnectorsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ConnectorsConfiguration.class);

    @Bean
    public ConnectorRegistry connectorRegistry(TeamConfig config, Environment env) {
        var sources = config.sources();
        var documentConnectors = new ArrayList<SourceConnector>();
        Optional<GitLabConnector> gitlab = Optional.empty();

        if (sources.confluence() != null) {
            documentConnectors.add(new ConfluenceConnector(
                    sources.confluence(), env.getProperty("CONFLUENCE_TOKEN", "")));
        }
        if (sources.gitlab() != null) {
            gitlab = Optional.of(new GitLabConnector(
                    sources.gitlab(), env.getProperty("GITLAB_TOKEN", "")));
            documentConnectors.add(gitlab.get());
        }
        if (sources.jira() != null) {
            documentConnectors.add(new JiraConnector(
                    sources.jira(), env.getProperty("JIRA_TOKEN", "")));
        }

        log.info("Connecteurs actifs : {}",
                documentConnectors.stream().map(SourceConnector::source).toList());
        return new ConnectorRegistry(documentConnectors, gitlab.map(g -> g));
    }
}
