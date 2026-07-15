package com.domwil.xrag.adapter.out.atlassian;

import com.domwil.xrag.adapter.out.ReadOnlyHttpGuard;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Résout le cloudId d'un site Atlassian via l'endpoint public
 * {@code {origin}/_edge/tenant_info}. Le cloudId identifie le tenant dans les URLs de
 * la passerelle api.atlassian.com (modes scoped token et OAuth). Résultat caché par
 * origin : un site = un seul cloudId, partagé par Confluence et Jira.
 */
public class AtlassianCloudIdResolver {

    private final RestClient http;
    private final ConcurrentMap<String, String> cacheByOrigin = new ConcurrentHashMap<>();

    public AtlassianCloudIdResolver(RestClient.Builder builder) {
        this(builder.requestInterceptor(new ReadOnlyHttpGuard()).build());
    }

    AtlassianCloudIdResolver(RestClient http) {
        this.http = http;
    }

    /**
     * @param siteBaseUrl base-url configurée de la source (ex. https://team.atlassian.net/wiki)
     * @return le cloudId du tenant (mis en cache par origin)
     */
    public String resolve(String siteBaseUrl) {
        return cacheByOrigin.computeIfAbsent(origin(siteBaseUrl), this::fetch);
    }

    private String fetch(String origin) {
        JsonNode info = http.get()
                .uri(origin + "/_edge/tenant_info")
                .retrieve()
                .body(JsonNode.class);
        String cloudId = info == null ? null : info.path("cloudId").asText(null);
        if (cloudId == null || cloudId.isBlank()) {
            throw new IllegalStateException(
                    "cloudId introuvable via " + origin + "/_edge/tenant_info");
        }
        return cloudId;
    }

    private static String origin(String baseUrl) {
        URI uri = URI.create(baseUrl);
        return uri.getScheme() + "://" + uri.getAuthority();
    }
}
