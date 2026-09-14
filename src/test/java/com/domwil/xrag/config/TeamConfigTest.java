package com.domwil.xrag.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TeamConfigTest {

    @Test
    void bindsTheCommittedExampleFile() throws IOException {
        TeamConfig config = bind("team-config.example.yml");

        assertThat(config.team()).isEqualTo("equipe-passerelle");

        assertThat(config.llm().provider()).isEqualTo("ollama");
        assertThat(config.llm().model()).isEqualTo("qwen2.5:7b-instruct");
        assertThat(config.llm().fallbackModel()).isEqualTo("qwen2.5:3b");
        assertThat(config.llm().embeddingModel()).isEqualTo("bge-m3");

        assertThat(config.sources().confluence().baseUrl()).isEqualTo("https://confluence.example.com");
        assertThat(config.sources().confluence().spaces()).containsExactly("PASS", "ARCHI");
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
    void bindsTheLocalGitModeWithDefaults() throws IOException {
        Path yaml = Files.createTempFile("team-config-git", ".yml");
        Files.writeString(yaml, """
                team: t
                llm: { provider: ollama, model: m }
                sources:
                  git:
                    root: /repos
                    branches: [main, develop]
                    web-base-url: https://gitlab.example.com/
                    merge-requests:
                      from-open-branches: false
                      rescan-days: 7
                  jira: { base-url: https://jira.example.com, projects: [PASS] }
                """);
        TeamConfig config = bind(yaml.toString());

        TeamConfig.Git git = config.sources().git();
        assertThat(config.sources().gitlab()).isNull();
        assertThat(config.sources().isSingleCodeSource()).isTrue();
        assertThat(git.root()).isEqualTo("/repos");
        assertThat(git.repos()).isEmpty();
        assertThat(git.branches()).containsExactly("main", "develop");
        assertThat(git.remote()).isEqualTo("origin");
        assertThat(git.webBaseUrl()).isEqualTo("https://gitlab.example.com"); // slash final retiré
        assertThat(git.maxFileKb()).isEqualTo(512);
        assertThat(git.mergeRequests().fromMergeCommits()).isTrue();
        assertThat(git.mergeRequests().fromLinearCommits()).isTrue();
        assertThat(git.mergeRequests().fromOpenBranches()).isFalse();
        assertThat(git.mergeRequests().openBranchesExclude()).contains("release/*", "renovate/*");
        assertThat(git.mergeRequests().rescanDays()).isEqualTo(7);
        assertThat(git.mergeRequests().maxChangedFiles()).isEqualTo(500);
    }

    @Test
    void gitlabApiAndLocalGitAreExclusive() {
        var both = new TeamConfig.Sources(null,
                new TeamConfig.Gitlab("https://gitlab.example.com", "grp", null),
                new TeamConfig.Git("/repos", null, null, null, null, null, null), null);
        assertThat(both.isSingleCodeSource()).isFalse();

        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        var violations = validator.validate(both);
        assertThat(violations).anySatisfy(v -> assertThat(v.getMessage()).contains("exclusifs"));
    }

    private static TeamConfig bind(String path) throws IOException {
        var propertySource = new YamlPropertySourceLoader()
                .load("team-config", new FileSystemResource(path))
                .get(0);
        var binder = new Binder(ConfigurationPropertySources.from(propertySource));
        return binder.bind("", Bindable.of(TeamConfig.class)).get();
    }
}
