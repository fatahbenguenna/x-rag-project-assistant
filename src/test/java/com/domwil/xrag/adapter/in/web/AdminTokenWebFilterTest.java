package com.domwil.xrag.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminTokenWebFilterTest {

    private static final String TOKEN = "s3cret";

    private final WebFilterChain chain = mock(WebFilterChain.class);

    private MockServerWebExchange run(AdminTokenWebFilter filter, MockServerHttpRequest request) {
        var exchange = MockServerWebExchange.from(request);
        when(chain.filter(any())).thenReturn(Mono.empty());
        filter.filter(exchange, chain).block();
        return exchange;
    }

    @Test
    void postAdminSansTokenRejeteEn401() {
        var exchange = run(new AdminTokenWebFilter(TOKEN),
                MockServerHttpRequest.post("/api/admin/sync").build());

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    void postAdminAvecMauvaisTokenRejete() {
        var exchange = run(new AdminTokenWebFilter(TOKEN),
                MockServerHttpRequest.post("/api/admin/enrich")
                        .header(AdminTokenWebFilter.HEADER, "faux").build());

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void postAdminAvecBonTokenPasse() {
        run(new AdminTokenWebFilter(TOKEN),
                MockServerHttpRequest.post("/api/admin/nightly")
                        .header(AdminTokenWebFilter.HEADER, TOKEN).build());

        verify(chain).filter(any());
    }

    @Test
    void getAdminResteLibreMemeAvecTokenConfigure() {
        // dashboard.html et préflight lisent l'état sans coût ni mutation
        run(new AdminTokenWebFilter(TOKEN),
                MockServerHttpRequest.get("/api/admin/indexing-status").build());

        verify(chain).filter(any());
    }

    @Test
    void horsAdminNonFiltre() {
        run(new AdminTokenWebFilter(TOKEN),
                MockServerHttpRequest.post("/v1/chat/completions").build());

        verify(chain).filter(any());
    }

    @Test
    void sansTokenConfigureToutPasse() {
        run(new AdminTokenWebFilter(""),
                MockServerHttpRequest.post("/api/admin/sync").build());

        verify(chain).filter(any());
    }
}
