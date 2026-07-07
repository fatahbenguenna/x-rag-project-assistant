package com.domwil.xrag.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
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

        assertThat(config.aliases()).containsEntry("easyloc", List.of("Easy Loc", "easy-loc", "EASYLOC"));

        assertThat(config.schedule().nightly()).isEqualTo("0 0 2 * * *");
        assertThat(config.extractors().java()).isTrue();
        assertThat(config.extractors().typescript()).isTrue();
        assertThat(config.extractors().python()).isFalse();
    }

    private static TeamConfig bind(String path) throws IOException {
        var propertySource = new YamlPropertySourceLoader()
                .load("team-config", new FileSystemResource(path))
                .get(0);
        var binder = new Binder(ConfigurationPropertySources.from(propertySource));
        return binder.bind("", Bindable.of(TeamConfig.class)).get();
    }
}
