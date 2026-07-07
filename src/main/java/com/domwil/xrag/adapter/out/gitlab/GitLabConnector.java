package com.domwil.xrag.adapter.out.gitlab;

import com.domwil.xrag.config.TeamConfig;
import com.domwil.xrag.domain.model.MergeRequestMeta;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.MergeRequestConnector;
import com.domwil.xrag.domain.port.SourceConnector;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Adapter GitLab : découverte automatique des repos du groupe configuré,
 * indexation du code des branches configurées, récolte des MRs.
 *
 * <p>Sync incrémentale code : liste des commits depuis {@code since} → fichiers
 * touchés → re-fetch de ces seuls fichiers (re-embedding minimal). Les MRs
 * utilisent {@code updated_after} côté API.
 */
public class GitLabConnector implements SourceConnector, MergeRequestConnector {

    public static final String SOURCE = "gitlab-code";

    private static final Logger log = LoggerFactory.getLogger(GitLabConnector.class);
    private static final int PAGE_SIZE = 100;
    /** Extensions indexées (code + doc + config notable). */
    private static final Set<String> INDEXED_EXTENSIONS = Set.of(
            "java", "kt", "ts", "html", "md", "adoc", "yml", "yaml", "properties", "sql", "xml", "json");

    private final RestClient http;
    private final TeamConfig.Gitlab config;

    public GitLabConnector(TeamConfig.Gitlab config, String token) {
        this(config, RestClient.builder()
                .baseUrl(config.baseUrl())
                .defaultHeader("PRIVATE-TOKEN", token)
                .build());
    }

    GitLabConnector(TeamConfig.Gitlab config, RestClient http) {
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
        for (JsonNode project : groupProjects()) {
            long projectId = project.path("id").asLong();
            String projectSlug = project.path("path").asText();
            String webUrl = project.path("web_url").asText();
            for (String branch : branchesOf(project)) {
                Set<String> paths = since == null
                        ? allFiles(projectId, branch)
                        : filesTouchedSince(projectId, branch, since);
                for (String filePath : paths) {
                    if (!indexable(filePath)) {
                        continue;
                    }
                    String content = rawFile(projectId, filePath, branch);
                    if (content == null) {
                        continue; // supprimé entre-temps : la réconciliation purgera
                    }
                    documents.add(new SourceDocument(
                            SOURCE,
                            projectSlug,
                            projectSlug + "@" + branch + "/" + filePath,
                            filePath,
                            content,
                            webUrl + "/-/blob/" + branch + "/" + filePath,
                            headSha(projectId, branch),
                            null,
                            Map.of("branch", branch, "file", filePath, "gitlabProjectId", projectId)));
                }
            }
        }
        return documents;
    }

    @Override
    public List<MergeRequestMeta> fetchUpdatedAfter(Instant since) {
        var mrs = new ArrayList<MergeRequestMeta>();
        paginate(page -> http.get()
                .uri(uri -> {
                    var builder = uri.path("/api/v4/groups/{group}/merge_requests")
                            .queryParam("scope", "all")
                            .queryParam("state", "all")
                            .queryParam("per_page", PAGE_SIZE)
                            .queryParam("page", page);
                    if (since != null) {
                        builder.queryParam("updated_after", since.toString());
                    }
                    return builder.build(config.group());
                })
                .retrieve()
                .body(JsonNode.class), mr -> mrs.add(toMergeRequest(mr)));
        return mrs;
    }

    private MergeRequestMeta toMergeRequest(JsonNode mr) {
        long projectId = mr.path("project_id").asLong();
        long iid = mr.path("iid").asLong();
        var labels = new ArrayList<String>();
        mr.path("labels").forEach(l -> labels.add(l.asText()));
        return new MergeRequestMeta(
                "gitlab:" + projectId + ":" + iid,
                projectSlugOf(projectId, mr),
                iid,
                mr.path("title").asText(),
                mr.path("description").asText(""),
                mr.path("state").asText(),
                mr.path("author").path("username").asText(null),
                mr.path("source_branch").asText(null),
                mr.path("target_branch").asText(null),
                mr.path("web_url").asText(null),
                labels,
                changedFiles(projectId, iid),
                parseInstant(mr.path("created_at").asText(null)),
                parseInstant(mr.path("updated_at").asText(null)),
                parseInstant(mr.path("merged_at").asText(null)));
    }

    /** Fichiers touchés par la MR (arêtes MODIFIES du graphe). */
    private List<String> changedFiles(long projectId, long iid) {
        try {
            JsonNode diffs = http.get()
                    .uri("/api/v4/projects/{id}/merge_requests/{iid}/diffs?per_page={per}", projectId, iid, PAGE_SIZE)
                    .retrieve()
                    .body(JsonNode.class);
            var files = new ArrayList<String>();
            diffs.forEach(d -> files.add(d.path("new_path").asText()));
            return files;
        } catch (Exception e) {
            log.warn("Diffs indisponibles pour MR {}!{} : {}", projectId, iid, e.getMessage());
            return List.of();
        }
    }

    private List<JsonNode> groupProjects() {
        var projects = new ArrayList<JsonNode>();
        paginate(page -> http.get()
                .uri("/api/v4/groups/{group}/projects?include_subgroups=true&archived=false&per_page={per}&page={page}",
                        config.group(), PAGE_SIZE, page)
                .retrieve()
                .body(JsonNode.class), projects::add);
        return projects;
    }

    /** Branches configurées effectivement présentes sur le projet (fallback : branche par défaut). */
    private List<String> branchesOf(JsonNode project) {
        long projectId = project.path("id").asLong();
        var existing = new ArrayList<String>();
        for (String branch : config.branches()) {
            if (headSha(projectId, branch) != null) {
                existing.add(branch);
            }
        }
        if (existing.isEmpty()) {
            String defaultBranch = project.path("default_branch").asText(null);
            if (defaultBranch != null) {
                existing.add(defaultBranch);
            }
        }
        return existing;
    }

    private Set<String> allFiles(long projectId, String ref) {
        var paths = new LinkedHashSet<String>();
        paginate(page -> http.get()
                .uri("/api/v4/projects/{id}/repository/tree?recursive=true&ref={ref}&per_page={per}&page={page}",
                        projectId, ref, PAGE_SIZE, page)
                .retrieve()
                .body(JsonNode.class),
                entry -> {
                    if ("blob".equals(entry.path("type").asText())) {
                        paths.add(entry.path("path").asText());
                    }
                });
        return paths;
    }

    private Set<String> filesTouchedSince(long projectId, String ref, Instant since) {
        var paths = new LinkedHashSet<String>();
        paginate(page -> http.get()
                .uri("/api/v4/projects/{id}/repository/commits?ref_name={ref}&since={since}&per_page={per}&page={page}",
                        projectId, ref, since.toString(), PAGE_SIZE, page)
                .retrieve()
                .body(JsonNode.class),
                commit -> {
                    JsonNode diff = http.get()
                            .uri("/api/v4/projects/{id}/repository/commits/{sha}/diff",
                                    projectId, commit.path("id").asText())
                            .retrieve()
                            .body(JsonNode.class);
                    diff.forEach(d -> paths.add(d.path("new_path").asText()));
                });
        return paths;
    }

    private String rawFile(long projectId, String filePath, String ref) {
        try {
            return http.get()
                    .uri(uri -> uri.path("/api/v4/projects/{id}/repository/files/{filePath}/raw")
                            .queryParam("ref", ref)
                            .build(projectId, filePath))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("Fichier illisible {} @{} (projet {}) : {}", filePath, ref, projectId, e.getMessage());
            return null;
        }
    }

    private String headSha(long projectId, String branch) {
        try {
            JsonNode node = http.get()
                    .uri(uri -> uri.path("/api/v4/projects/{id}/repository/branches/{branch}")
                            .build(projectId, branch))
                    .retrieve()
                    .body(JsonNode.class);
            return node.path("commit").path("id").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String projectSlugOf(long projectId, JsonNode mr) {
        // path_with_namespace absent du payload MR : approximation stable par référence web.
        String ref = mr.path("references").path("full").asText(null); // "group/projet!42"
        if (ref != null && ref.contains("!")) {
            String path = ref.substring(0, ref.indexOf('!'));
            return path.substring(path.lastIndexOf('/') + 1);
        }
        return String.valueOf(projectId);
    }

    private static boolean indexable(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 && INDEXED_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase());
    }

    /** Boucle de pagination GitLab : s'arrête quand une page revient incomplète. */
    private void paginate(Function<Integer, JsonNode> fetch, java.util.function.Consumer<JsonNode> consume) {
        int page = 1;
        while (true) {
            JsonNode result = fetch.apply(page);
            if (result == null || !result.isArray() || result.isEmpty()) {
                return;
            }
            result.forEach(consume);
            if (result.size() < PAGE_SIZE) {
                return;
            }
            page++;
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null ? null : java.time.OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
