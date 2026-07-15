package com.domwil.xrag.adapter.out.atlassian;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AtlassianConnectionFactoryTest {

    @Mock
    private AtlassianCloudIdResolver cloudIdResolver;

    private AtlassianConnectionFactory factory;

    @BeforeEach
    void setUp() {
        factory = new AtlassianConnectionFactory(cloudIdResolver, RestClient.builder());
    }

    private static SourceCredentials tokenUserCookie(String token, String user, String cookie) {
        return new SourceCredentials(token, user, cookie, "", "");
    }

    @Test
    void cookiePrioritaire_connexionDirecteEnModeCookie() {
        var conn = factory.create(tokenUserCookie("token", "user", "JSESSIONID=x"),
                "https://team.atlassian.net/wiki", AtlassianProduct.CONFLUENCE);

        assertThat(conn).isInstanceOf(DirectConnection.class);
        assertThat(conn.mode()).isEqualTo("cookie");
        assertThat(conn.baseUrl()).isEqualTo("https://team.atlassian.net/wiki");
    }

    @Test
    void userDefini_connexionDirecteEnModeBasic() {
        var conn = factory.create(tokenUserCookie("api-token", "svc", ""),
                "https://team.atlassian.net/wiki", AtlassianProduct.CONFLUENCE);

        assertThat(conn).isInstanceOf(DirectConnection.class);
        assertThat(conn.mode()).isEqualTo("basic");
    }

    @Test
    void tokenSeulSurCloud_connexionPasserelleScoped() {
        when(cloudIdResolver.resolve("https://team.atlassian.net/wiki")).thenReturn("cloud-42");

        var conn = factory.create(tokenUserCookie("svc-token", "", ""),
                "https://team.atlassian.net/wiki", AtlassianProduct.CONFLUENCE);

        assertThat(conn).isInstanceOf(GatewayTokenConnection.class);
        assertThat(conn.mode()).isEqualTo("scoped");
        assertThat(conn.baseUrl()).isEqualTo("https://api.atlassian.com/ex/confluence/cloud-42/wiki");
    }

    @Test
    void tokenSeulSurCloud_jira_baseSansSuffixeWiki() {
        when(cloudIdResolver.resolve("https://team.atlassian.net")).thenReturn("cloud-42");

        var conn = factory.create(tokenUserCookie("svc-token", "", ""),
                "https://team.atlassian.net", AtlassianProduct.JIRA);

        assertThat(conn.baseUrl()).isEqualTo("https://api.atlassian.com/ex/jira/cloud-42");
    }

    @Test
    void oauthClientCredentials_connexionPasserelleEnModeOAuth() {
        when(cloudIdResolver.resolve("https://team.atlassian.net/wiki")).thenReturn("cloud-42");

        var conn = factory.create(new SourceCredentials("", "", "", "client-id", "client-secret"),
                "https://team.atlassian.net/wiki", AtlassianProduct.CONFLUENCE);

        assertThat(conn).isInstanceOf(GatewayOAuthConnection.class);
        assertThat(conn.mode()).isEqualTo("oauth");
        assertThat(conn.baseUrl()).isEqualTo("https://api.atlassian.com/ex/confluence/cloud-42/wiki");
    }

    @Test
    void tokenSeulSurDataCenter_connexionDirecteEnModeBearer() {
        var conn = factory.create(tokenUserCookie("pat", "", ""),
                "https://confluence.interne.example", AtlassianProduct.CONFLUENCE);

        assertThat(conn).isInstanceOf(DirectConnection.class);
        assertThat(conn.mode()).isEqualTo("bearer");
    }
}
