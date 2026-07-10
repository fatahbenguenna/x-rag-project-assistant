package com.domwil.xrag.adapter.out.jira;

import com.domwil.xrag.adapter.out.SourceAuth;
import com.domwil.xrag.config.TeamConfig;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.SourceConnector;
import tools.jackson.databind.JsonNode;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Adapter Jira (REST API 2, JQL). Sync incrémentale par {@code updated >= ...}. */
public class JiraConnector implements SourceConnector {

    public static final String SOURCE = "jira";

    private static final DateTimeFormatter JQL_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final int PAGE_SIZE = 100;
    private static final String FIELDS =
            "summary,description,status,issuetype,project,labels,created,updated,issuelinks";

    private final RestClient http;
    private final TeamConfig.Jira config;

    public JiraConnector(TeamConfig.Jira config, SourceAuth auth) {
        this(config, RestClient.builder()
                .baseUrl(config.baseUrl())
                .defaultHeader(auth.headerName(), auth.headerValue())
                .build());
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
        int startAt = 0;
        while (true) {
            JsonNode page = search(jql, startAt);
            JsonNode issues = page.path("issues");
            issues.forEach(issue -> documents.add(toDocument(issue)));
            startAt += issues.size();
            if (startAt >= page.path("total").asInt() || issues.isEmpty()) {
                return documents;
            }
        }
    }

    private JsonNode search(String jql, int startAt) {
        return http.get()
                .uri(uri -> uri.path("/rest/api/2/search")
                        .queryParam("jql", jql)
                        .queryParam("fields", FIELDS)
                        .queryParam("maxResults", PAGE_SIZE)
                        .queryParam("startAt", startAt)
                        .build())
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
        String description = fields.path("description").asText("");
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

    private static Instant parseInstant(String value) {
        try {
            // Format Jira : 2024-05-13T10:12:00.000+0200
            return value == null ? null : java.time.OffsetDateTime
                    .parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
                    .toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
