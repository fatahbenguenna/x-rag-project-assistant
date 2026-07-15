package com.domwil.xrag.adapter.out.atlassian;

/**
 * Produit Atlassian Cloud accessible via la passerelle api.atlassian.com. Le slug et
 * le suffixe de path déterminent la base des appels API en mode scoped/OAuth :
 * {@code https://api.atlassian.com/ex/{slug}/{cloudId}{pathSuffix}}.
 */
public enum AtlassianProduct {

    /** Confluence : les endpoints REST vivent sous /wiki. */
    CONFLUENCE("confluence", "/wiki"),

    /** Jira : endpoints REST à la racine du contexte. */
    JIRA("jira", "");

    private final String slug;
    private final String pathSuffix;

    AtlassianProduct(String slug, String pathSuffix) {
        this.slug = slug;
        this.pathSuffix = pathSuffix;
    }

    public String slug() {
        return slug;
    }

    public String pathSuffix() {
        return pathSuffix;
    }
}
