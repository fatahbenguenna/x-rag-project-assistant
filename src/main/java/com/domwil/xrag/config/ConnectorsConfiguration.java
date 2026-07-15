package com.domwil.xrag.config;

import com.domwil.xrag.adapter.out.atlassian.AtlassianCloudIdResolver;
import com.domwil.xrag.adapter.out.atlassian.AtlassianConnection;
import com.domwil.xrag.adapter.out.atlassian.AtlassianConnectionFactory;
import com.domwil.xrag.adapter.out.atlassian.AtlassianProduct;
import com.domwil.xrag.adapter.out.atlassian.SourceCredentials;
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
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Optional;

/**
 * Active les connecteurs déclarés dans team-config.yml. Les tokens viennent
 * de l'environnement (.env), jamais du YAML.
 */
@Configuration
public class ConnectorsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ConnectorsConfiguration.class);

    // CloudIdResolver et client OAuth utilisent des builders SÉPARÉS : le premier ajoute
    // le garde-fou lecture seule (GET), le second doit pouvoir POSTer vers le token endpoint.
    private final AtlassianConnectionFactory connectionFactory = new AtlassianConnectionFactory(
            new AtlassianCloudIdResolver(RestClient.builder()),
            RestClient.builder());

    @Bean
    public ConnectorRegistry connectorRegistry(TeamConfig config, Environment env) {
        var sources = config.sources();
        var documentConnectors = new ArrayList<SourceConnector>();
        Optional<GitLabConnector> gitlab = Optional.empty();

        if (sources.confluence() != null) {
            var connection = atlassianConnection(env, "CONFLUENCE",
                    sources.confluence().baseUrl(), AtlassianProduct.CONFLUENCE);
            log.info("Confluence : authentification {}", connection.mode());
            documentConnectors.add(new ConfluenceConnector(sources.confluence(), connection));
        }
        if (sources.gitlab() != null) {
            gitlab = Optional.of(new GitLabConnector(
                    sources.gitlab(), env.getProperty("GITLAB_TOKEN", "")));
            documentConnectors.add(gitlab.get());
        }
        if (sources.jira() != null) {
            var connection = atlassianConnection(env, "JIRA",
                    sources.jira().baseUrl(), AtlassianProduct.JIRA);
            log.info("Jira : authentification {}", connection.mode());
            documentConnectors.add(new JiraConnector(sources.jira(), connection));
        }

        log.info("Connecteurs actifs : {}",
                documentConnectors.stream().map(SourceConnector::source).toList());
        return new ConnectorRegistry(documentConnectors, gitlab.map(g -> g));
    }

    private AtlassianConnection atlassianConnection(Environment env, String prefix,
                                                    String baseUrl, AtlassianProduct product) {
        var creds = new SourceCredentials(
                env.getProperty(prefix + "_TOKEN", ""),
                env.getProperty(prefix + "_USER", ""),
                env.getProperty(prefix + "_COOKIE", ""),
                env.getProperty(prefix + "_OAUTH_CLIENT_ID", ""),
                env.getProperty(prefix + "_OAUTH_CLIENT_SECRET", ""));
        return connectionFactory.create(creds, baseUrl, product);
    }
}
