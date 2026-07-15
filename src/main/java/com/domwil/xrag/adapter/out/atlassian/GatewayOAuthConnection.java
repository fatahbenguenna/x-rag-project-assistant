package com.domwil.xrag.adapter.out.atlassian;

import org.springframework.web.client.RestClient;

/**
 * Connexion « passerelle » en OAuth 2.0 client credentials. Comme le mode scoped, les
 * appels passent par {@code api.atlassian.com/ex/{produit}/{cloudId}}, mais le token
 * porteur est dynamique : un interceptor pose à chaque requête un access token frais
 * fourni par {@link OAuthTokenProvider} (rafraîchi toutes les ~60 min).
 */
public final class GatewayOAuthConnection implements AtlassianConnection {

    private final String baseUrl;
    private final OAuthTokenProvider tokenProvider;

    public GatewayOAuthConnection(String baseUrl, OAuthTokenProvider tokenProvider) {
        this.baseUrl = baseUrl;
        this.tokenProvider = tokenProvider;
    }

    @Override
    public String mode() {
        return "oauth";
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public void applyAuth(RestClient.Builder builder) {
        builder.requestInterceptor((request, body, execution) -> {
            request.getHeaders().setBearerAuth(tokenProvider.currentToken());
            return execution.execute(request, body);
        });
    }
}
