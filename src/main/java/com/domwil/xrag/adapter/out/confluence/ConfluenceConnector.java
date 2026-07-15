package com.domwil.xrag.adapter.out.confluence;

import com.domwil.xrag.adapter.out.ReadOnlyHttpGuard;
import com.domwil.xrag.adapter.out.atlassian.AtlassianConnection;
import com.domwil.xrag.config.TeamConfig;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.SourceConnector;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adapter Confluence (REST API v2). Pour chaque space configuré : résolution
 * {@code spaceKey -> space-id}, puis listing des pages trié par date de modification
 * décroissante ({@code body-format=storage}), paginé par cursor. La sync incrémentale
 * s'arrête dès qu'une page est antérieure à {@code since} (les pages étant triées
 * décroissant). L'API v1 ({@code /rest/api/content}) a été retirée par Atlassian.
 */
public class ConfluenceConnector implements SourceConnector {

    public static final String SOURCE = "confluence";

    private static final int PAGE_SIZE = 50;

    private final RestClient http;
    private final TeamConfig.Confluence config;

    public ConfluenceConnector(TeamConfig.Confluence config, AtlassianConnection connection) {
        this(config, buildClient(connection));
    }

    private static RestClient buildClient(AtlassianConnection connection) {
        var builder = RestClient.builder()
                .baseUrl(connection.baseUrl())
                .requestInterceptor(new ReadOnlyHttpGuard());
        connection.applyAuth(builder);
        return builder.build();
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
        var documents = new ArrayList<SourceDocument>();
        for (String spaceKey : config.spaces()) {
            String spaceId = resolveSpaceId(spaceKey);
            if (spaceId != null) {
                fetchPages(spaceKey, spaceId, since, documents);
            }
        }
        return documents;
    }

    private String resolveSpaceId(String spaceKey) {
        JsonNode response = http.get()
                .uri(uri -> uri.path("/api/v2/spaces").queryParam("keys", spaceKey).build())
                .retrieve()
                .body(JsonNode.class);
        JsonNode results = response.path("results");
        return results.isEmpty() ? null : results.get(0).path("id").asText(null);
    }

    private void fetchPages(String spaceKey, String spaceId, Instant since, List<SourceDocument> documents) {
        String cursor = null;
        while (true) {
            JsonNode page = listPages(spaceId, cursor);
            boolean reachedOlderThanSince = false;
            for (JsonNode result : page.path("results")) {
                Instant modified = parseInstant(result.path("version").path("createdAt").asText(null));
                if (since != null && modified != null && modified.isBefore(since)) {
                    reachedOlderThanSince = true;
                    break;
                }
                documents.add(toDocument(spaceKey, result));
            }
            cursor = reachedOlderThanSince ? null : nextCursor(page);
            if (cursor == null) {
                return;
            }
        }
    }

    private JsonNode listPages(String spaceId, String cursor) {
        return http.get()
                .uri(uri -> {
                    uri.path("/api/v2/pages")
                            .queryParam("space-id", spaceId)
                            .queryParam("body-format", "storage")
                            .queryParam("sort", "-modified-date")
                            .queryParam("limit", PAGE_SIZE);
                    if (cursor != null) {
                        uri.queryParam("cursor", cursor);
                    }
                    return uri.build();
                })
                .retrieve()
                .body(JsonNode.class);
    }

    private SourceDocument toDocument(String spaceKey, JsonNode page) {
        JsonNode version = page.path("version");
        String storage = page.path("body").path("storage").path("value").asText("");
        return new SourceDocument(
                SOURCE,
                null,
                page.path("id").asText(),
                page.path("title").asText(),
                HtmlText.toText(storage),
                config.baseUrl() + page.path("_links").path("webui").asText(),
                version.path("number").asText(),
                parseInstant(version.path("createdAt").asText(null)),
                Map.of("space", spaceKey));
    }

    /** Extrait le cursor de pagination depuis {@code _links.next} (URL relative), ou null. */
    private static String nextCursor(JsonNode page) {
        String next = page.path("_links").path("next").asText(null);
        if (next == null) {
            return null;
        }
        int start = next.indexOf("cursor=");
        if (start < 0) {
            return null;
        }
        String cursor = next.substring(start + "cursor=".length());
        int end = cursor.indexOf('&');
        if (end >= 0) {
            cursor = cursor.substring(0, end);
        }
        return URLDecoder.decode(cursor, StandardCharsets.UTF_8);
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
