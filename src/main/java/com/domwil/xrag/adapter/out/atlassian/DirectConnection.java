package com.domwil.xrag.adapter.out.atlassian;

import com.domwil.xrag.adapter.out.SourceAuth;
import org.springframework.web.client.RestClient;

/**
 * Connexion « domaine direct » : cookie, basic ou bearer-PAT. La base-url reste celle
 * configurée dans team-config.yml et l'authentification est un header statique résolu
 * par {@link SourceAuth} (Confluence/Jira Server/Data Center, ou API token classique Cloud).
 */
public final class DirectConnection implements AtlassianConnection {

    private final String baseUrl;
    private final SourceAuth auth;

    public DirectConnection(String baseUrl, SourceAuth auth) {
        this.baseUrl = baseUrl;
        this.auth = auth;
    }

    @Override
    public String mode() {
        return auth.mode();
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public void applyAuth(RestClient.Builder builder) {
        builder.defaultHeader(auth.headerName(), auth.headerValue());
    }
}
