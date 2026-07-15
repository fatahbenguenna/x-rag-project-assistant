package com.domwil.xrag.adapter.out.atlassian;

import com.domwil.xrag.adapter.out.SourceAuth;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DirectConnectionTest {

    @Test
    void exposeLeModeEtLaBaseUrlConfiguree() {
        var conn = new DirectConnection("https://wiki.example/wiki",
                SourceAuth.resolve("pat", "", ""));

        assertThat(conn.mode()).isEqualTo("bearer");
        assertThat(conn.baseUrl()).isEqualTo("https://wiki.example/wiki");
    }

    @Test
    void appliqueLeHeaderDAuthentificationSurLesRequetes() {
        var auth = SourceAuth.resolve("api-token", "svc", "");
        var conn = new DirectConnection("https://wiki.example", auth);

        var builder = RestClient.builder().baseUrl(conn.baseUrl());
        conn.applyAuth(builder);
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = builder.build();

        server.expect(requestTo("https://wiki.example/probe"))
                .andExpect(header("Authorization", auth.headerValue()))
                .andRespond(withSuccess());

        client.get().uri("/probe").retrieve().toBodilessEntity();
        server.verify();
    }
}
