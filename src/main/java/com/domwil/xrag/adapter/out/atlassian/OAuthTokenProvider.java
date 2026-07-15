package com.domwil.xrag.adapter.out.atlassian;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Fournit un access token OAuth 2.0 obtenu par le flow « client credentials » (2LO)
 * d'Atlassian pour un compte de service : {@code POST auth.atlassian.com/oauth/token}.
 * Le token (valable ~60 min) est mis en cache et rafraîchi automatiquement peu avant son
 * expiration. Thread-safe : le batch nocturne et les webhooks peuvent le solliciter en
 * concurrence.
 */
public class OAuthTokenProvider {

    private static final String TOKEN_ENDPOINT = "https://auth.atlassian.com/oauth/token";
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;

    private String accessToken;
    private Instant expiresAt = Instant.EPOCH;

    public OAuthTokenProvider(RestClient.Builder builder, String clientId, String clientSecret) {
        this(builder.build(), clientId, clientSecret, Clock.systemUTC());
    }

    OAuthTokenProvider(RestClient http, String clientId, String clientSecret, Clock clock) {
        this.http = http;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    /** Access token valide, rafraîchi si nécessaire (proche ou au-delà de l'expiration). */
    public synchronized String currentToken() {
        Instant now = clock.instant();
        if (accessToken == null || !now.isBefore(expiresAt)) {
            refresh(now);
        }
        return accessToken;
    }

    private void refresh(Instant now) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type", "client_credentials");

        JsonNode response = http.post()
                .uri(TOKEN_ENDPOINT)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);

        String token = response == null ? null : response.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("access_token absent de la réponse OAuth Atlassian");
        }
        this.accessToken = token;
        this.expiresAt = now.plusSeconds(response.path("expires_in").asLong(3600L)).minus(EXPIRY_MARGIN);
    }
}
