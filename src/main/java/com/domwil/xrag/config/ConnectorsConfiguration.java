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
            var confluence = sources.confluence();
            warnIfLegacy("confluence", confluence.legacyBaseUrl());
            var connection = atlassianConnection(env, "CONFLUENCE",
                    confluence.connection(), AtlassianProduct.CONFLUENCE);
            documentConnectors.add(new ConfluenceConnector(confluence, connection));
        }
        if (sources.gitlab() != null) {
            warnIfLegacy("gitlab", sources.gitlab().legacyBaseUrl());
            gitlab = Optional.of(new GitLabConnector(
                    sources.gitlab(), env.getProperty("GITLAB_TOKEN", "")));
            documentConnectors.add(gitlab.get());
        }
        if (sources.jira() != null) {
            var jira = sources.jira();
            warnIfLegacy("jira", jira.legacyBaseUrl());
            var connection = atlassianConnection(env, "JIRA",
                    jira.connection(), AtlassianProduct.JIRA);
            documentConnectors.add(new JiraConnector(jira, connection));
        }

        log.info("Connecteurs actifs : {}",
                documentConnectors.stream().map(SourceConnector::source).toList());
        return new ConnectorRegistry(documentConnectors, gitlab.map(g -> g));
    }

    private AtlassianConnection atlassianConnection(Environment env, String prefix,
                                                    TeamConfig.Connection config,
                                                    AtlassianProduct product) {
        var creds = new SourceCredentials(
                env.getProperty(prefix + "_TOKEN", ""),
                env.getProperty(prefix + "_USER", ""),
                env.getProperty(prefix + "_COOKIE", ""),
                env.getProperty(prefix + "_OAUTH_CLIENT_ID", ""),
                env.getProperty(prefix + "_OAUTH_CLIENT_SECRET", ""));
        var mode = config.auth();
        if (mode != null) {
            creds.requireFor(mode, prefix);
        }
        var connection = connectionFactory.create(creds, config.baseUrl(), product, mode);
        if (mode != null) {
            log.info("{} : authentification {}", product.slug(), connection.mode());
        } else {
            log.info("{} : authentification {} (déduite des variables .env — la fixer avec "
                    + "connection.auth dans team-config.yml)", product.slug(), connection.mode());
        }
        return connection;
    }

    private static void warnIfLegacy(String source, String legacyBaseUrl) {
        if (legacyBaseUrl != null && !legacyBaseUrl.isBlank()) {
            log.warn("sources.{}.base-url est la forme historique : la déplacer sous "
                    + "sources.{}.connection.base-url (voir team-config.example.yml)", source, source);
        }
    }
}
