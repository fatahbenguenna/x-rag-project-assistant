package com.domwil.xrag.adapter.out.atlassian;

import com.domwil.xrag.config.TeamConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Credentials d'une source Atlassian, lus depuis l'environnement ({@code {PREFIX}_*}).
 * Regroupés en un objet dédié pour garder les signatures courtes (règle « max 3 paramètres »).
 */
public record SourceCredentials(String token, String user, String cookie,
                                String oauthClientId, String oauthClientSecret) {

    public boolean hasToken() {
        return notBlank(token);
    }

    public boolean hasUser() {
        return notBlank(user);
    }

    public boolean hasCookie() {
        return notBlank(cookie);
    }

    public boolean hasOAuth() {
        return notBlank(oauthClientId) && notBlank(oauthClientSecret);
    }

    /**
     * Vérifie qu'un mode d'auth explicite a ses secrets : échec au démarrage plutôt qu'un
     * 401 à la première sync.
     *
     * @param envPrefix préfixe des variables .env ({@code CONFLUENCE}, {@code JIRA})
     */
    public void requireFor(TeamConfig.AuthMode mode, String envPrefix) {
        var missing = new ArrayList<String>();
        switch (mode) {
            case COOKIE -> addIfBlank(missing, cookie, envPrefix + "_COOKIE");
            case BASIC -> {
                addIfBlank(missing, user, envPrefix + "_USER");
                addIfBlank(missing, token, envPrefix + "_TOKEN");
            }
            case BEARER, SCOPED -> addIfBlank(missing, token, envPrefix + "_TOKEN");
            case OAUTH -> {
                addIfBlank(missing, oauthClientId, envPrefix + "_OAUTH_CLIENT_ID");
                addIfBlank(missing, oauthClientSecret, envPrefix + "_OAUTH_CLIENT_SECRET");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("auth: " + mode.label() + " exige "
                    + String.join(", ", missing) + " dans .env (vide)");
        }
    }

    private static void addIfBlank(List<String> missing, String value, String variable) {
        if (!notBlank(value)) {
            missing.add(variable);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
