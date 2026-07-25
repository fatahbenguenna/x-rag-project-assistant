package com.domwil.xrag.adapter.in.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Protège les endpoints d'administration mutants et coûteux ({@code POST /api/admin/**} :
 * sync full = re-embedding complet, nightly, enrich = appels LLM en rafale) par un secret
 * partagé {@code ADMIN_TOKEN} (env, comme les autres secrets), attendu dans le header
 * {@code X-Admin-Token}. Sans token configuré : accès libre (dev) + avertissement au
 * démarrage — l'app est publiée sur le LAN d'équipe (revue 2026-07, H7).
 *
 * <p>Les {@code GET} restent libres : le dashboard ({@code /dashboard.html}) et le
 * préflight ne lisent que l'état, sans coût ni mutation.
 */
@Component
public class AdminTokenWebFilter implements WebFilter {

    public static final String HEADER = "X-Admin-Token";

    private static final Logger log = LoggerFactory.getLogger(AdminTokenWebFilter.class);
    private static final String ADMIN_PATH_PREFIX = "/api/admin";

    private final String adminToken;

    public AdminTokenWebFilter(@Value("${ADMIN_TOKEN:}") String adminToken) {
        this.adminToken = adminToken == null ? "" : adminToken.strip();
        if (this.adminToken.isEmpty()) {
            log.warn("ADMIN_TOKEN non configuré : les endpoints POST /api/admin/** sont ouverts "
                    + "à tout le réseau qui atteint ce port — à réserver au développement "
                    + "(voir .env.example)");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        boolean protectedCall = !adminToken.isEmpty()
                && request.getPath().value().startsWith(ADMIN_PATH_PREFIX)
                && !HttpMethod.GET.equals(request.getMethod());
        if (!protectedCall || tokenMatches(request.getHeaders().getFirst(HEADER))) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    /** Comparaison en temps constant — un secret ne se compare jamais avec equals(). */
    private boolean tokenMatches(String provided) {
        return provided != null && MessageDigest.isEqual(
                adminToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
