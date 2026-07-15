package com.domwil.xrag.adapter.out.atlassian;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Connexion « passerelle » à token scoped (compte de service Atlassian Cloud). Les
 * appels passent par {@code api.atlassian.com/ex/{produit}/{cloudId}} avec un token
 * porteur statique. C'est le seul mode accepté par les tokens créés par un compte de
 * service — le domaine direct les rejette.
 */
public final class GatewayTokenConnection implements AtlassianConnection {

    private final String baseUrl;
    private final String token;

    public GatewayTokenConnection(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    @Override
    public String mode() {
        return "scoped";
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public void applyAuth(RestClient.Builder builder) {
        builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
