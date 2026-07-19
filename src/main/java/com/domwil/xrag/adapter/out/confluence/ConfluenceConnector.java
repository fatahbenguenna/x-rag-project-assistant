package com.domwil.xrag.adapter.out.confluence;

import com.domwil.xrag.adapter.out.ReadOnlyHttpGuard;
import com.domwil.xrag.adapter.out.atlassian.AtlassianConnection;
import com.domwil.xrag.adapter.out.atlassian.AtlassianPlatform;
import com.domwil.xrag.config.TeamConfig;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adapter Confluence bi-plateforme. <b>Cloud</b> : API v2 (spaces -> pages, cursor, body
 * storage). <b>Data Center / Server</b> : API v1 (CQL search, pagination start/limit). La
 * plateforme est déduite de la base-url ({@code *.atlassian.net} = Cloud).
 */
public class ConfluenceConnector implements SourceConnector {

    public static final String SOURCE = "confluence";

    private static final Logger log = LoggerFactory.getLogger(ConfluenceConnector.class);
    private static final int PAGE_SIZE = 50;
    private static final DateTimeFormatter CQL_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneOffset.UTC);

    private final RestClient http;
    private final TeamConfig.Confluence config;
    private final AtlassianPlatform platform;

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
        this.platform = AtlassianPlatform.of(config.baseUrl());
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<SourceDocument> fetchChangedSince(Instant since) {
        return platform.isCloud() ? fetchCloud(since) : fetchDataCenter(since);
    }

    // ---- Cloud : API v2 --------------------------------------------------------

    private List<SourceDocument> fetchCloud(Instant since) {
        var documents = new ArrayList<SourceDocument>();
        for (String spaceKey : config.spaces()) {
            String spaceId = resolveSpaceId(spaceKey);
            if (spaceId != null) {
                fetchCloudPages(spaceKey, spaceId, since, documents);
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

    private void fetchCloudPages(String spaceKey, String spaceId, Instant since, List<SourceDocument> documents) {
        String cursor = null;
        while (true) {
            JsonNode page = listCloudPages(spaceId, cursor);
            boolean reachedOlderThanSince = false;
            for (JsonNode result : page.path("results")) {
                Instant modified = parseInstant(result.path("version").path("createdAt").asText(null));
                if (since != null && modified != null && modified.isBefore(since)) {
                    reachedOlderThanSince = true;
                    break;
                }
                documents.add(toCloudDocument(spaceKey, result));
            }
            cursor = reachedOlderThanSince ? null : nextCursor(page);
            if (cursor == null) {
                return;
            }
        }
    }

    private JsonNode listCloudPages(String spaceId, String cursor) {
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

    private SourceDocument toCloudDocument(String spaceKey, JsonNode page) {
        JsonNode version = page.path("version");
        String pageId = page.path("id").asText();
        String storage = page.path("body").path("storage").path("value").asText("");
        return new SourceDocument(
                SOURCE,
                null,
                pageId,
                page.path("title").asText(),
                HtmlText.toText(storage) + fetchCloudComments(pageId),
                config.baseUrl() + page.path("_links").path("webui").asText(),
                version.path("number").asText(),
                parseInstant(version.path("createdAt").asText(null)),
                Map.of("space", spaceKey));
    }

    /** Commentaires de bas de page (footer) d'une page Cloud, ajoutés au contenu indexé. */
    private String fetchCloudComments(String pageId) {
        try {
            JsonNode response = http.get()
                    .uri(uri -> uri.path("/api/v2/pages/{id}/footer-comments")
                            .queryParam("body-format", "storage")
                            .queryParam("limit", 100)
                            .build(pageId))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode results = response == null ? null : response.path("results");
            if (results == null || !results.isArray() || results.isEmpty()) {
                return "";
            }
            var sb = new StringBuilder("\n\n## Commentaires");
            for (JsonNode comment : results) {
                String text = HtmlText.toText(comment.path("body").path("storage").path("value").asText(""));
                if (!text.isBlank()) {
                    sb.append("\n- ").append(text);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Commentaires Confluence indisponibles pour la page {} : {}", pageId, e.getMessage());
            return "";
        }
    }

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

    // ---- Data Center / Server : API v1 (CQL) -----------------------------------

    private List<SourceDocument> fetchDataCenter(Instant since) {
        String cql = "type = page and space in (" + String.join(", ", config.spaces()) + ")";
        if (since != null) {
            cql += " and lastModified >= \"" + CQL_DATE.format(since) + "\"";
        }
        var documents = new ArrayList<SourceDocument>();
        int start = 0;
        while (true) {
            JsonNode page = searchV1(cql, start);
            JsonNode results = page.path("results");
            results.forEach(result -> documents.add(toDataCenterDocument(result)));
            if (results.size() < PAGE_SIZE) {
                return documents;
            }
            start += PAGE_SIZE;
        }
    }

    private JsonNode searchV1(String cql, int start) {
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

    private SourceDocument toDataCenterDocument(JsonNode page) {
        JsonNode version = page.path("version");
        return new SourceDocument(
                SOURCE,
                null,
                page.path("id").asText(),
                page.path("title").asText(),
                HtmlText.toText(page.path("body").path("storage").path("value").asText()),
                config.baseUrl() + page.path("_links").path("webui").asText(),
                version.path("number").asText(),
                parseInstant(version.path("when").asText(null)),
                Map.of("space", page.path("space").path("key").asText()));
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
