package com.domwil.xrag.adapter.out;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Garde-fou structurel : les clients HTTP des sources (Confluence, Jira,
 * GitLab) sont physiquement incapables d'émettre autre chose que GET/HEAD.
 * Les credentials d'indexation — y compris des cookies de session personnels
 * qui auraient des droits d'écriture — ne peuvent donc jamais servir à créer,
 * modifier ou supprimer quoi que ce soit sur les plateformes, même en cas de
 * bug ou de régression future dans un connecteur.
 */
public final class ReadOnlyHttpGuard implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        HttpMethod method = request.getMethod();
        if (!HttpMethod.GET.equals(method) && !HttpMethod.HEAD.equals(method)) {
            throw new IllegalStateException(
                    "Lecture seule : " + method + " " + request.getURI()
                            + " refusé — les connecteurs de sources n'émettent jamais d'écriture");
        }
        return execution.execute(request, body);
    }
}
