package com.domwil.xrag.adapter.out.atlassian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class OAuthTokenProviderTest {

    private static final String ENDPOINT = "https://auth.atlassian.com/oauth/token";
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private Clock clock;

    @Test
    void obtientLAccessTokenViaClientCredentials() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new OAuthTokenProvider(builder.build(), "cid", "secret", FIXED);

        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"AT1\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        assertThat(provider.currentToken()).isEqualTo("AT1");
        server.verify();
    }

    @Test
    void metEnCacheLeTokenJusquAExpiration() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new OAuthTokenProvider(builder.build(), "cid", "secret", FIXED);

        server.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"access_token\":\"AT1\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        assertThat(provider.currentToken()).isEqualTo("AT1");
        assertThat(provider.currentToken()).isEqualTo("AT1");
        server.verify();
    }

    @Test
    void rafraichitLeTokenApresExpiration() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        when(clock.instant()).thenReturn(t0, t0.plusSeconds(3600));

        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var provider = new OAuthTokenProvider(builder.build(), "cid", "secret", clock);

        server.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"access_token\":\"AT1\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"access_token\":\"AT2\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        assertThat(provider.currentToken()).isEqualTo("AT1");
        assertThat(provider.currentToken()).isEqualTo("AT2");
        server.verify();
    }
}
