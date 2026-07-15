package com.domwil.xrag.adapter.out.jira;

import com.domwil.xrag.config.TeamConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JiraConnectorTest {

    private static final String PAGE_1 = """
            {"issues":[{"id":"1","key":"FPSSUITE-1","fields":{
              "summary":"Webhook cuisine",
              "description":{"type":"doc","content":[{"type":"paragraph","content":[
                {"type":"text","text":"Envoi"},{"type":"text","text":"cuisine"}]}]},
              "status":{"name":"Open"},"issuetype":{"name":"Task"},
              "project":{"key":"FPSSUITE"},"labels":[],
              "updated":"2026-07-14T17:20:16.158+0200","issuelinks":[]}}],
             "nextPageToken":"tok1","isLast":false}""";

    private static final String PAGE_2 = """
            {"issues":[{"id":"2","key":"FPSSUITE-2","fields":{
              "summary":"Correctif",
              "description":null,
              "status":{"name":"Closed"},"issuetype":{"name":"Bug"},
              "project":{"key":"FPSSUITE"},"labels":[],
              "updated":"2026-07-15T09:00:00.000+0200","issuelinks":[]}}],
             "isLast":true}""";

    @Test
    void paginateParNextPageTokenEtExtraitLaDescriptionAdf() {
        var builder = RestClient.builder().baseUrl("https://jira.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var config = new TeamConfig.Jira("https://jira.test", List.of("FPSSUITE"));
        var connector = new JiraConnector(config, builder.build());

        server.expect(requestTo(containsString("/rest/api/3/search/jql")))
                .andRespond(withSuccess(PAGE_1, MediaType.APPLICATION_JSON));
        server.expect(queryParam("nextPageToken", "tok1"))
                .andRespond(withSuccess(PAGE_2, MediaType.APPLICATION_JSON));

        var docs = connector.fetchChangedSince(null);

        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).path()).isEqualTo("FPSSUITE-1");
        assertThat(docs.get(0).content()).contains("Webhook cuisine").contains("Envoi cuisine");
        assertThat(docs.get(0).url()).isEqualTo("https://jira.test/browse/FPSSUITE-1");
        assertThat(docs.get(1).path()).isEqualTo("FPSSUITE-2");
        assertThat(docs.get(1).metadata()).containsEntry("status", "Closed");
        server.verify();
    }
}
