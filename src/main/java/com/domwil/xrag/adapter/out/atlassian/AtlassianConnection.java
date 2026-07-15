package com.domwil.xrag.adapter.out.atlassian;

import org.springframework.web.client.RestClient;

/**
 * Profil de connexion à une source Atlassian (Confluence/Jira). Contrairement à une
 * simple authentification par header, il détermine AUSSI la base-url effective des
 * appels API : domaine direct pour les modes cookie/basic/bearer, passerelle
 * {@code api.atlassian.com/ex/{produit}/{cloudId}} pour les tokens scoped et OAuth.
 *
 * <p>Cette base-url effective ne concerne que les appels API. Les liens de citation
 * présentés à l'utilisateur restent construits sur la base-url web configurée.
 */
public interface AtlassianConnection {

    /** Mode d'authentification résolu (cookie, basic, bearer, scoped, oauth) — pour le log. */
    String mode();

    /** Base-url effective des appels API (domaine direct ou passerelle Atlassian). */
    String baseUrl();

    /** Applique l'authentification (header statique ou interceptor) sur le client HTTP. */
    void applyAuth(RestClient.Builder builder);
}
