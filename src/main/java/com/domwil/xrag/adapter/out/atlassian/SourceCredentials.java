package com.domwil.xrag.adapter.out.atlassian;

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

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
