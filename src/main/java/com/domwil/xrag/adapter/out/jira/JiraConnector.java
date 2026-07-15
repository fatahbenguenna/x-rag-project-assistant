package com.domwil.xrag.adapter.out.jira;

import com.domwil.xrag.adapter.out.ReadOnlyHttpGuard;
import com.domwil.xrag.adapter.out.atlassian.AtlassianConnection;
import com.domwil.xrag.config.TeamConfig;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.SourceConnector;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Adapter Jira (REST API v3, JQL). Sync incrémentale par {@code updated >= ...}.
 * Utilise {@code /rest/api/3/search/jql} avec pagination par {@code nextPageToken} —
 * l'ancien {@code /rest/api/2/search} a été supprimé par Atlassian (HTTP 410).
 */
public class JiraConnector implements SourceConnector {

    public static final String SOURCE = "jira";

    private static final DateTimeFormatter JQL_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final int PAGE_SIZE = 100;
    private static final String FIELDS =
            "summary,description,status,issuetype,project,labels,created,updated,issuelinks";

    private final RestClient http;
    private final TeamConfig.Jira config;

    public JiraConnector(TeamConfig.Jira config, AtlassianConnection connection) {
        this(config, buildClient(connection));
    }

    private static RestClient buildClient(AtlassianConnection connection) {
        var builder = RestClient.builder()
                .baseUrl(connection.baseUrl())
                .requestInterceptor(new ReadOnlyHttpGuard());
        connection.applyAuth(builder);
        return builder.build();
    }

    JiraConnector(TeamConfig.Jira config, RestClient http) {
        this.config = config;
        this.http = http;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<SourceDocument> fetchChangedSince(Instant since) {
        String jql = "project in (" + String.join(", ", config.projects()) + ")";
        if (since != null) {
            jql += " and updated >= \"" + JQL_DATE.format(since) + "\"";
        }
        jql += " order by updated asc";

        var documents = new ArrayList<SourceDocument>();
        String pageToken = null;
        do {
            JsonNode page = search(jql, pageToken);
            page.path("issues").forEach(issue -> documents.add(toDocument(issue)));
            pageToken = page.path("nextPageToken").asText(null);
        } while (pageToken != null && !pageToken.isBlank());
        return documents;
    }

    private JsonNode search(String jql, String pageToken) {
        return http.get()
                .uri(uri -> {
                    uri.path("/rest/api/3/search/jql")
                            .queryParam("jql", jql)
                            .queryParam("fields", FIELDS)
                            .queryParam("maxResults", PAGE_SIZE);
                    if (pageToken != null && !pageToken.isBlank()) {
                        uri.queryParam("nextPageToken", pageToken);
                    }
                    return uri.build();
                })
                .retrieve()
                .body(JsonNode.class);
    }

    private SourceDocument toDocument(JsonNode issue) {
        String key = issue.path("key").asText();
        JsonNode fields = issue.path("fields");

        var linkedKeys = new ArrayList<String>();
        fields.path("issuelinks").forEach(link -> {
            JsonNode other = link.has("outwardIssue") ? link.path("outwardIssue") : link.path("inwardIssue");
            String linkedKey = other.path("key").asText(null);
            if (linkedKey != null) {
                linkedKeys.add(linkedKey);
            }
        });

        var metadata = new HashMap<String, Object>();
        metadata.put("issueKey", key);
        metadata.put("jiraProject", fields.path("project").path("key").asText());
        metadata.put("status", fields.path("status").path("name").asText());
        metadata.put("issueType", fields.path("issuetype").path("name").asText());
        metadata.put("linkedIssues", List.copyOf(linkedKeys));

        String summary = fields.path("summary").asText("");
        String description = extractText(fields.path("description"));
        return new SourceDocument(
                SOURCE,
                null,
                key,
                key + " — " + summary,
                (summary + "\n\n" + description).strip(),
                config.baseUrl() + "/browse/" + key,
                fields.path("updated").asText(null),
                parseInstant(fields.path("updated").asText(null)),
                metadata);
    }

    /**
     * Extrait le texte plein d'un champ Jira : chaîne simple, ou document ADF (Atlassian
     * Document Format) tel que renvoyé par l'API v3 pour {@code description}.
     */
    private static String extractText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.has("text")) {
            return node.path("text").asText("");
        }
        var sb = new StringBuilder();
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode child : content) {
                String text = extractText(child);
                if (!text.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    private static Instant parseInstant(String value) {
        try {
            // Format Jira : 2026-07-14T17:20:16.158+0200
            return value == null ? null : java.time.OffsetDateTime
                    .parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
                    .toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
