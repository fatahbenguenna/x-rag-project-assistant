package com.domwil.xrag.adapter.out.atlassian;

import com.domwil.xrag.config.TeamConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceCredentialsTest {

    @Test
    void basicSansUser_nommeLaVariableManquante() {
        var creds = new SourceCredentials("token", "", "", "", "");

        assertThatThrownBy(() -> creds.requireFor(TeamConfig.AuthMode.BASIC, "JIRA"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("auth: basic exige JIRA_USER dans .env (vide)");
    }

    @Test
    void oauthIncomplet_nommeToutesLesVariablesManquantes() {
        var creds = new SourceCredentials("", "", "", "", "");

        assertThatThrownBy(() -> creds.requireFor(TeamConfig.AuthMode.OAUTH, "CONFLUENCE"))
                .hasMessage("auth: oauth exige CONFLUENCE_OAUTH_CLIENT_ID, "
                        + "CONFLUENCE_OAUTH_CLIENT_SECRET dans .env (vide)");
    }

    @Test
    void cookieRenseigne_ok() {
        var creds = new SourceCredentials("", "", "JSESSIONID=x", "", "");

        assertThatCode(() -> creds.requireFor(TeamConfig.AuthMode.COOKIE, "JIRA")).doesNotThrowAnyException();
    }

    @Test
    void bearerEtScoped_exigentLeToken() {
        var creds = new SourceCredentials(" ", "", "", "", "");

        assertThatThrownBy(() -> creds.requireFor(TeamConfig.AuthMode.BEARER, "JIRA"))
                .hasMessage("auth: bearer exige JIRA_TOKEN dans .env (vide)");
        assertThatThrownBy(() -> creds.requireFor(TeamConfig.AuthMode.SCOPED, "JIRA"))
                .hasMessage("auth: scoped exige JIRA_TOKEN dans .env (vide)");
    }
}
