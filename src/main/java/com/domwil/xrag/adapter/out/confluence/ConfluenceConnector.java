package com.domwil.xrag.adapter.out.confluence;

import com.domwil.xrag.config.TeamConfig;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.SourceConnector;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adapter Confluence (REST API Server/DC). Sync incrémentale par CQL
 * {@code lastModified >= ...} ; la comparaison fine de version.number est
 * faite à l'upsert via SourceDocument.version.
 */
public class ConfluenceConnector implements SourceConnector {

    public static final String SOURCE = "confluence";

    private static final DateTimeFormatter CQL_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneOffset.UTC);
    private static final int PAGE_SIZE = 50;

    private final RestClient http;
    private final TeamConfig.Confluence config;

    public ConfluenceConnector(TeamConfig.Confluence config, String token) {
        this(config, RestClient.builder()
                .baseUrl(config.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build());
    }

    ConfluenceConnector(TeamConfig.Confluence config, RestClient http) {
        this.config = config;
        this.http = http;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<SourceDocument> fetchChangedSince(Instant since) {
        String cql = "type = page and space in (" + String.join(", ", config.spaces()) + ")";
        if (since != null) {
            cql += " and lastModified >= \"" + CQL_DATE.format(since) + "\"";
        }

        var documents = new ArrayList<SourceDocument>();
        int start = 0;
        while (true) {
            JsonNode page = search(cql, start);
            JsonNode results = page.path("results");
            results.forEach(result -> documents.add(toDocument(result)));
            if (results.size() < PAGE_SIZE) {
                return documents;
            }
            start += PAGE_SIZE;
        }
    }

    private JsonNode search(String cql, int start) {
        return http.get()
                .uri(uri -> uri.path("/rest/api/content/search")
                        .queryParam("cql", cql)
                        .queryParam("expand", "body.storage,version,space")
                        .queryParam("limit", PAGE_SIZE)
                        .queryParam("start", start)
                        .build())
                .retrieve()
                .body(JsonNode.class);
    }

    private SourceDocument toDocument(JsonNode page) {
        String id = page.path("id").asText();
        JsonNode version = page.path("version");
        return new SourceDocument(
                SOURCE,
                null,
                id,
                page.path("title").asText(),
                HtmlText.toText(page.path("body").path("storage").path("value").asText()),
                config.baseUrl() + page.path("_links").path("webui").asText(),
                version.path("number").asText(),
                parseInstant(version.path("when").asText(null)),
                Map.of("space", page.path("space").path("key").asText()));
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null ? null : java.time.OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
