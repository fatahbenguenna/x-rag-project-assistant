package com.domwil.xrag.adapter.out.atlassian;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AtlassianCloudIdResolverTest {

    @Test
    void resoutLeCloudIdDepuisTenantInfo() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var resolver = new AtlassianCloudIdResolver(builder.build());

        server.expect(requestTo("https://team.atlassian.net/_edge/tenant_info"))
                .andRespond(withSuccess("{\"cloudId\":\"abc-123\"}", MediaType.APPLICATION_JSON));

        assertThat(resolver.resolve("https://team.atlassian.net/wiki")).isEqualTo("abc-123");
        server.verify();
    }

    @Test
    void metEnCacheParOrigin_confluenceEtJiraPartagentUneSeuleRequete() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var resolver = new AtlassianCloudIdResolver(builder.build());

        server.expect(ExpectedCount.once(), requestTo("https://team.atlassian.net/_edge/tenant_info"))
                .andRespond(withSuccess("{\"cloudId\":\"abc-123\"}", MediaType.APPLICATION_JSON));

        assertThat(resolver.resolve("https://team.atlassian.net/wiki")).isEqualTo("abc-123");
        assertThat(resolver.resolve("https://team.atlassian.net")).isEqualTo("abc-123");
        server.verify();
    }
}
