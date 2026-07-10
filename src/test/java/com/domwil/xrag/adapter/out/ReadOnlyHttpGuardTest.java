package com.domwil.xrag.adapter.out;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadOnlyHttpGuardTest {

    private final ReadOnlyHttpGuard guard = new ReadOnlyHttpGuard();
    private final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);

    private HttpRequest request(HttpMethod method) {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getURI()).thenReturn(URI.create("https://confluence.example.com/rest/api/content/123"));
        return request;
    }

    @Test
    void getPasse() throws Exception {
        guard.intercept(request(HttpMethod.GET), new byte[0], execution);
        verify(execution).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void toutesLesEcrituresSontBloqueesAvantEnvoi() {
        for (HttpMethod method : new HttpMethod[]{
                HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH}) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> guard.intercept(request(method), new byte[0], execution))
                    .withMessageContaining("Lecture seule")
                    .withMessageContaining(method.name());
        }
        // Aucune requête d'écriture n'a atteint la couche réseau
        org.mockito.Mockito.verifyNoInteractions(execution);
    }

    @Test
    void messageIdentifieLaCible() {
        assertThatIllegalStateException()
                .isThrownBy(() -> guard.intercept(request(HttpMethod.DELETE), new byte[0], execution))
                .satisfies(e -> assertThat(e.getMessage()).contains("confluence.example.com"));
    }
}
