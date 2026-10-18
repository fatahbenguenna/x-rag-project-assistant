package com.domwil.xrag.adapter.out.atlassian;

import com.domwil.xrag.adapter.out.SourceAuth;
import com.domwil.xrag.config.TeamConfig;
import org.springframework.web.client.RestClient;

/**
 * Construit le profil de {@link AtlassianConnection} adéquat selon les credentials
 * fournis ({@link SourceCredentials}) et la nature du site.
 *
 * <p>Un mode explicite ({@code connection.auth} dans team-config.yml) est appliqué tel quel.
 * Sans lui, priorité de résolution historique :
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

    /**
     * Mode explicite ({@code connection.auth}) : appliqué tel quel, sans déduction — les
     * autres variables renseignées sont ignorées. {@code null} = déduction historique.
     * Les secrets du mode doivent avoir été vérifiés ({@link SourceCredentials#requireFor}).
     */
    public AtlassianConnection create(SourceCredentials creds, String baseUrl, AtlassianProduct product,
                                      TeamConfig.AuthMode mode) {
        if (mode == null) {
            return create(creds, baseUrl, product);
        }
        return switch (mode) {
            case COOKIE -> new DirectConnection(baseUrl, SourceAuth.cookie(creds.cookie()));
            case BASIC -> new DirectConnection(baseUrl, SourceAuth.basic(creds.user(), creds.token()));
            case BEARER -> new DirectConnection(baseUrl, SourceAuth.bearer(creds.token()));
            case SCOPED -> {
                requireCloud(mode, baseUrl);
                yield new GatewayTokenConnection(gatewayBaseUrl(baseUrl, product), creds.token());
            }
            case OAUTH -> {
                requireCloud(mode, baseUrl);
                yield oauth(creds, baseUrl, product);
            }
        };
    }

    /** scoped et oauth passent par la passerelle api.atlassian.com : Cloud uniquement. */
    private static void requireCloud(TeamConfig.AuthMode mode, String baseUrl) {
        if (!AtlassianPlatform.of(baseUrl).isCloud()) {
            throw new IllegalStateException("auth: " + mode.label() + " n'existe que sur Atlassian Cloud "
                    + "(*.atlassian.net) — pour un PAT Data Center, utiliser auth: bearer");
        }
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
