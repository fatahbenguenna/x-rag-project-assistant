package com.domwil.xrag.adapter.out.atlassian;

/**
 * Plateforme d'hébergement Atlassian, déterminée par la base-url. Elle décide de la
 * famille d'endpoints REST utilisée par les connecteurs : le <b>Cloud</b> impose les API
 * récentes (Confluence v2, Jira v3 {@code search/jql}), le <b>Data Center / Server</b> les
 * API historiques (Confluence v1 CQL, Jira v2 {@code search}) — les deux ne se recouvrent pas.
 */
public enum AtlassianPlatform {

    CLOUD,
    DATA_CENTER;

    /** Un site {@code *.atlassian.net} est Cloud ; tout autre domaine est self-hosted. */
    public static AtlassianPlatform of(String baseUrl) {
        return baseUrl != null && baseUrl.contains(".atlassian.net") ? CLOUD : DATA_CENTER;
    }

    public boolean isCloud() {
        return this == CLOUD;
    }
}
