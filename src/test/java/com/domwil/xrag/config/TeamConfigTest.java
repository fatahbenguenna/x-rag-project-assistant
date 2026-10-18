package com.domwil.xrag.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeamConfigTest {

    @Test
    void bindsTheCommittedExampleFile() throws IOException {
        TeamConfig config = bind("team-config.example.yml");

        assertThat(config.team()).isEqualTo("equipe-passerelle");

        assertThat(config.llm().provider()).isEqualTo("ollama");
        assertThat(config.llm().model()).isEqualTo("qwen2.5:7b-instruct");
        assertThat(config.llm().fallbackModel()).isEqualTo("qwen2.5:3b");
        assertThat(config.llm().embeddingModel()).isEqualTo("bge-m3");

        var confluence = config.sources().confluence();
        assertThat(confluence.legacyBaseUrl()).isNull();
        assertThat(confluence.connection().type()).isEqualTo(TeamConfig.Transport.REST);
        assertThat(confluence.connection().baseUrl()).isEqualTo("https://confluence.example.com");
        assertThat(confluence.connection().auth()).isEqualTo(TeamConfig.AuthMode.BEARER);
        assertThat(config.sources().confluence().spaces()).containsExactly("PASS", "ARCHI");
        assertThat(config.sources().gitlab().connection().baseUrl()).isEqualTo("https://gitlab.example.com");
        assertThat(config.sources().gitlab().group()).isEqualTo("passerelle");
        assertThat(config.sources().gitlab().branches()).containsExactly("main", "develop");
        assertThat(config.sources().jira().projects()).containsExactly("PASS", "INFRA");

        assertThat(config.aliases()).containsEntry("fpskds", List.of("FPS KDS", "fps-kds", "FPSKDS"));

        assertThat(config.schedule().nightly()).isEqualTo("0 0 2 * * *");
        assertThat(config.extractors().java()).isTrue();
        assertThat(config.extractors().typescript()).isTrue();
        assertThat(config.extractors().python()).isFalse();
    }

    @Test
    void bindsTheTestbedFile() throws IOException {
        TeamConfig config = bind("testbed/team-config.testbed.yml");

        assertThat(config.sources().jira().connection().baseUrl()).isEqualTo("https://jira.example.com");
        assertThat(config.sources().jira().connection().auth()).isNull();
    }

    @Test
    void legacyFlatBaseUrl_isReadAsRestConnection() {
        var jira = bindJira(Map.of(
                "sources.jira.base-url", "https://jira.example.com",
                "sources.jira.projects[0]", "PASS"));

        assertThat(jira.legacyBaseUrl()).isEqualTo("https://jira.example.com");
        assertThat(jira.connection()).isEqualTo(TeamConfig.Connection.rest("https://jira.example.com"));
    }

    @Test
    void connectionBlock_bindsTypeAndAuthCaseInsensitively() {
        var jira = bindJira(Map.of(
                "sources.jira.connection.type", "rest",
                "sources.jira.connection.base-url", "https://team.atlassian.net",
                "sources.jira.connection.auth", "scoped",
                "sources.jira.projects[0]", "PASS"));

        assertThat(jira.connection().type()).isEqualTo(TeamConfig.Transport.REST);
        assertThat(jira.connection().auth()).isEqualTo(TeamConfig.AuthMode.SCOPED);
    }

    @Test
    void connectionTypeDefaultsToRest() {
        var jira = bindJira(Map.of(
                "sources.jira.connection.base-url", "https://jira.example.com",
                "sources.jira.projects[0]", "PASS"));

        assertThat(jira.connection().type()).isEqualTo(TeamConfig.Transport.REST);
    }

    @Test
    void bothForms_failFastAsAmbiguous() {
        assertThatThrownBy(() -> bindJira(Map.of(
                "sources.jira.base-url", "https://a.example.com",
                "sources.jira.connection.base-url", "https://b.example.com",
                "sources.jira.projects[0]", "PASS")))
                .hasRootCauseMessage("sources.jira : base-url déclaré deux fois "
                        + "(forme historique et connection.base-url) — ne garder que connection.base-url");
    }

    @Test
    void noBaseUrl_failsFast() {
        assertThatThrownBy(() -> bindJira(Map.of("sources.jira.projects[0]", "PASS")))
                .hasRootCauseMessage("sources.jira : connection.base-url manquant");
    }

    @Test
    void unknownAuthMode_failsFast() {
        assertThatThrownBy(() -> bindJira(Map.of(
                "sources.jira.connection.base-url", "https://jira.example.com",
                "sources.jira.connection.auth", "kerberos",
                "sources.jira.projects[0]", "PASS")))
                .isInstanceOf(BindException.class);
    }

    @Test
    void gitlabAuth_isRejected() {
        assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(Map.of(
                "sources.gitlab.connection.base-url", "https://gitlab.example.com",
                "sources.gitlab.connection.auth", "bearer",
                "sources.gitlab.group", "passerelle")))
                .bind("sources.gitlab", Bindable.of(TeamConfig.Gitlab.class)).get())
                .rootCause().hasMessageStartingWith("sources.gitlab.connection.auth : sans objet");
    }

    private static TeamConfig.Jira bindJira(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("sources.jira", Bindable.of(TeamConfig.Jira.class)).get();
    }

    private static TeamConfig bind(String path) throws IOException {
        var propertySource = new YamlPropertySourceLoader()
                .load("team-config", new FileSystemResource(path))
                .get(0);
        var binder = new Binder(ConfigurationPropertySources.from(propertySource));
        return binder.bind("", Bindable.of(TeamConfig.class)).get();
    }
}
