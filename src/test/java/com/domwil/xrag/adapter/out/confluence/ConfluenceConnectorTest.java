package com.domwil.xrag.adapter.out.confluence;

import com.domwil.xrag.config.TeamConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ConfluenceConnectorTest {

    private static final String CLOUD = "https://team.atlassian.net/wiki";
    private static final String DATA_CENTER = "https://confluence.interne.example";

    // --- Cloud (API v2) ---
    private static final String SPACE = """
            {"results":[{"id":"123","key":"FPSSUITE","name":"FPS Suite"}],"_links":{}}""";
    private static final String PAGES_1 = """
            {"results":[{"id":"65735","status":"current","title":"Page A","spaceId":"123",
              "version":{"number":3,"createdAt":"2026-07-14T10:00:00.000Z"},
              "body":{"storage":{"value":"<p>Contenu A</p>","representation":"storage"}},
              "_links":{"webui":"/spaces/FPSSUITE/pages/65735/Page+A"}}],
             "_links":{"next":"/wiki/api/v2/pages?space-id=123&cursor=CUR2"}}""";
    private static final String PAGES_2 = """
            {"results":[{"id":"65800","status":"current","title":"Page B","spaceId":"123",
              "version":{"number":1,"createdAt":"2026-07-13T09:00:00.000Z"},
              "body":{"storage":{"value":"<p>Contenu B</p>","representation":"storage"}},
              "_links":{"webui":"/spaces/FPSSUITE/pages/65800/Page+B"}}],
             "_links":{}}""";
    private static final String PAGES_MIXED = """
            {"results":[
              {"id":"1","status":"current","title":"Récente","spaceId":"123",
               "version":{"number":2,"createdAt":"2026-07-14T10:00:00.000Z"},
               "body":{"storage":{"value":"<p>R</p>"}},"_links":{"webui":"/x/1"}},
              {"id":"2","status":"current","title":"Vieille","spaceId":"123",
               "version":{"number":1,"createdAt":"2026-07-10T10:00:00.000Z"},
               "body":{"storage":{"value":"<p>V</p>"}},"_links":{"webui":"/x/2"}}],
             "_links":{"next":"/wiki/api/v2/pages?space-id=123&cursor=CURX"}}""";

    private static final String COMMENTS = """
            {"results":[{"body":{"storage":{"value":"<p>Décision importante</p>"}}}]}""";
    private static final String NO_COMMENTS = """
            {"results":[]}""";

    // --- Data Center (API v1, CQL) ---
    private static final String V1_RESULTS = """
            {"results":[{"id":"111","title":"Page DC",
              "body":{"storage":{"value":"<p>Contenu DC</p>"}},
              "version":{"number":2,"when":"2026-07-14T10:00:00.000+02:00"},
              "space":{"key":"FPSSUITE"},
              "_links":{"webui":"/display/FPSSUITE/Page+DC"}}],"size":1}""";

    @Test
    void cloud_resoutSpaceIdListePagesEtPagineParCursor() {
        var builder = RestClient.builder().baseUrl(CLOUD);
        var server = MockRestServiceServer.bindTo(builder).build();
        var connector = new ConfluenceConnector(new TeamConfig.Confluence(CLOUD, List.of("FPSSUITE")), builder.build());

        server.expect(requestTo(containsString("/api/v2/spaces")))
                .andExpect(queryParam("keys", "FPSSUITE"))
                .andRespond(withSuccess(SPACE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/api/v2/pages")))
                .andExpect(queryParam("space-id", "123"))
                .andRespond(withSuccess(PAGES_1, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/pages/65735/footer-comments")))
                .andRespond(withSuccess(COMMENTS, MediaType.APPLICATION_JSON));
        server.expect(queryParam("cursor", "CUR2"))
                .andRespond(withSuccess(PAGES_2, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/pages/65800/footer-comments")))
                .andRespond(withSuccess(NO_COMMENTS, MediaType.APPLICATION_JSON));

        var docs = connector.fetchChangedSince(null);

        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).path()).isEqualTo("65735");
        assertThat(docs.get(0).content()).contains("Contenu A")
                .contains("Commentaires").contains("Décision importante");
        assertThat(docs.get(0).url()).isEqualTo(CLOUD + "/spaces/FPSSUITE/pages/65735/Page+A");
        assertThat(docs.get(0).metadata()).containsEntry("space", "FPSSUITE");
        server.verify();
    }

    @Test
    void cloud_arreteLaPaginationDesQuUnePageEstAnterieureASince() {
        var builder = RestClient.builder().baseUrl(CLOUD);
        var server = MockRestServiceServer.bindTo(builder).build();
        var connector = new ConfluenceConnector(new TeamConfig.Confluence(CLOUD, List.of("FPSSUITE")), builder.build());

        server.expect(requestTo(containsString("/api/v2/spaces")))
                .andRespond(withSuccess(SPACE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/api/v2/pages")))
                .andRespond(withSuccess(PAGES_MIXED, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/pages/1/footer-comments")))
                .andRespond(withSuccess(NO_COMMENTS, MediaType.APPLICATION_JSON));

        var docs = connector.fetchChangedSince(Instant.parse("2026-07-12T00:00:00Z"));

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).path()).isEqualTo("1");
        server.verify();
    }

    @Test
    void dataCenter_utiliseLApiV1Cql() {
        var builder = RestClient.builder().baseUrl(DATA_CENTER);
        var server = MockRestServiceServer.bindTo(builder).build();
        var connector = new ConfluenceConnector(new TeamConfig.Confluence(DATA_CENTER, List.of("FPSSUITE")), builder.build());

        server.expect(requestTo(containsString("/rest/api/content/search")))
                .andRespond(withSuccess(V1_RESULTS, MediaType.APPLICATION_JSON));

        var docs = connector.fetchChangedSince(null);

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).path()).isEqualTo("111");
        assertThat(docs.get(0).content()).contains("Contenu DC");
        assertThat(docs.get(0).url()).isEqualTo(DATA_CENTER + "/display/FPSSUITE/Page+DC");
        assertThat(docs.get(0).metadata()).containsEntry("space", "FPSSUITE");
        server.verify();
    }
}
