package com.domwil.xrag.adapter.out.atlassian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class GatewayOAuthConnectionTest {

    @Mock
    private OAuthTokenProvider tokenProvider;

    @Test
    void exposeLeModeOAuthEtLaBasePasserelle() {
        var conn = new GatewayOAuthConnection("https://api.atlassian.com/ex/jira/cid", tokenProvider);

        assertThat(conn.mode()).isEqualTo("oauth");
        assertThat(conn.baseUrl()).isEqualTo("https://api.atlassian.com/ex/jira/cid");
    }

    @Test
    void poseUnBearerFraisAChaqueRequete() {
        when(tokenProvider.currentToken()).thenReturn("AT-frais");
        var conn = new GatewayOAuthConnection("https://api.atlassian.com/ex/jira/cid", tokenProvider);

        var builder = RestClient.builder().baseUrl(conn.baseUrl());
        conn.applyAuth(builder);
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = builder.build();

        server.expect(requestTo("https://api.atlassian.com/ex/jira/cid/probe"))
                .andExpect(header("Authorization", "Bearer AT-frais"))
                .andRespond(withSuccess());

        client.get().uri("/probe").retrieve().toBodilessEntity();
        server.verify();
    }
}
