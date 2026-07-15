package com.domwil.xrag.adapter.out.atlassian;

import com.domwil.xrag.adapter.out.SourceAuth;
import org.springframework.web.client.RestClient;

/**
 * Construit le profil de {@link AtlassianConnection} adéquat selon les credentials
 * fournis ({@link SourceCredentials}) et la nature du site.
 *
 * <p>Priorité de résolution :
 * <ol>
 *   <li><b>cookie</b> — sessions self-hosted (domaine direct) ;</li>
 *   <li><b>oauth</b> — client_id + client_secret : OAuth 2.0 client credentials, passerelle ;</li>
 *   <li><b>basic</b> — user + token : API token classique Cloud ou compte DC (domaine direct) ;</li>
 *   <li><b>scoped</b> — token seul + site Cloud ({@code *.atlassian.net}) : compte de service, passerelle ;</li>
 *   <li><b>bearer</b> — token seul + site self-hosted : PAT Data Center (domaine direct).</li>
 * </ol>
 */
public final class AtlassianConnectionFactory {

    private static final String GATEWAY_PREFIX = "https://api.atlassian.com/ex/";

    private final AtlassianCloudIdResolver cloudIdResolver;
    private final RestClient.Builder oauthClientBuilder;

    public AtlassianConnectionFactory(AtlassianCloudIdResolver cloudIdResolver,
                                      RestClient.Builder oauthClientBuilder) {
        this.cloudIdResolver = cloudIdResolver;
        this.oauthClientBuilder = oauthClientBuilder;
    }

    public AtlassianConnection create(SourceCredentials creds, String baseUrl, AtlassianProduct product) {
        if (creds.hasCookie()) {
            return direct(creds, baseUrl);
        }
        if (creds.hasOAuth()) {
            return oauth(creds, baseUrl, product);
        }
        if (creds.hasUser()) {
            return direct(creds, baseUrl);
        }
        if (creds.hasToken() && AtlassianPlatform.of(baseUrl).isCloud()) {
            return new GatewayTokenConnection(gatewayBaseUrl(baseUrl, product), creds.token());
        }
        return direct(creds, baseUrl);
    }

    private AtlassianConnection direct(SourceCredentials creds, String baseUrl) {
        return new DirectConnection(baseUrl,
                SourceAuth.resolve(creds.token(), creds.user(), creds.cookie()));
    }

    private AtlassianConnection oauth(SourceCredentials creds, String baseUrl, AtlassianProduct product) {
        var tokenProvider = new OAuthTokenProvider(
                oauthClientBuilder, creds.oauthClientId(), creds.oauthClientSecret());
        return new GatewayOAuthConnection(gatewayBaseUrl(baseUrl, product), tokenProvider);
    }

    private String gatewayBaseUrl(String baseUrl, AtlassianProduct product) {
        String cloudId = cloudIdResolver.resolve(baseUrl);
        return GATEWAY_PREFIX + product.slug() + "/" + cloudId + product.pathSuffix();
    }
}
