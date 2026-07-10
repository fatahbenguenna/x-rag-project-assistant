package com.domwil.xrag.adapter.out;

import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Résolution du mode d'authentification HTTP des sources Confluence/Jira,
 * à partir des variables d'environnement (.env). Priorité décroissante :
 * <ol>
 *   <li><b>cookie</b> — chaîne Cookie brute copiée d'une session navigateur
 *       authentifiée (SSO/certificat). Mode dev/validation : expire avec la
 *       session, le health check du batch signalera l'expiration ;</li>
 *   <li><b>basic</b> — compte de service Data Center ou Atlassian Cloud
 *       (email + API token) ;</li>
 *   <li><b>bearer</b> — PAT Data Center (défaut, comportement historique).</li>
 * </ol>
 */
public record SourceAuth(String mode, String headerName, String headerValue) {

    public static SourceAuth resolve(String token, String user, String cookie) {
        if (cookie != null && !cookie.isBlank()) {
            return new SourceAuth("cookie", HttpHeaders.COOKIE, cookie.trim());
        }
        if (user != null && !user.isBlank()) {
            String credentials = Base64.getEncoder().encodeToString(
                    (user + ":" + token).getBytes(StandardCharsets.UTF_8));
            return new SourceAuth("basic", HttpHeaders.AUTHORIZATION, "Basic " + credentials);
        }
        return new SourceAuth("bearer", HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
