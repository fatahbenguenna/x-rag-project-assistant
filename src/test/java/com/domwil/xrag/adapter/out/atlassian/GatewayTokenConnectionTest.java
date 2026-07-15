package com.domwil.xrag.adapter.out.atlassian;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GatewayTokenConnectionTest {

    @Test
    void exposeLeModeScopedEtLaBasePasserelle() {
        var conn = new GatewayTokenConnection(
                "https://api.atlassian.com/ex/confluence/cid/wiki", "tok");

        assertThat(conn.mode()).isEqualTo("scoped");
        assertThat(conn.baseUrl()).isEqualTo("https://api.atlassian.com/ex/confluence/cid/wiki");
    }

    @Test
    void poseLeTokenPorteurSurLesRequetes() {
        var conn = new GatewayTokenConnection("https://api.atlassian.com/ex/jira/cid", "mon-token");

        var builder = RestClient.builder().baseUrl(conn.baseUrl());
        conn.applyAuth(builder);
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = builder.build();

        server.expect(requestTo("https://api.atlassian.com/ex/jira/cid/probe"))
                .andExpect(header("Authorization", "Bearer mon-token"))
                .andRespond(withSuccess());

        client.get().uri("/probe").retrieve().toBodilessEntity();
        server.verify();
    }
}
