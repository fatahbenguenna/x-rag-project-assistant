package com.domwil.xrag.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    private final TextChunker chunker = new TextChunker();

    @Test
    void shortContentIsASingleChunk() {
        assertThat(chunker.split("Un petit document.")).containsExactly("Un petit document.");
    }

    @Test
    void splitsOnParagraphsWithoutExceedingMaxSize() {
        String paragraph = "Lorem ipsum dolor sit amet. ".repeat(30).strip(); // ~840 caractères
        String content = paragraph + "\n\n" + paragraph + "\n\n" + paragraph;

        List<String> chunks = chunker.split(content);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(1800));
        assertThat(String.join(" ", chunks)).contains("Lorem ipsum");
    }

    @Test
    void hardSplitsOversizedParagraphsWithOverlap() {
        String giant = "x".repeat(4000);

        List<String> chunks = chunker.split(giant);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks.getFirst()).hasSize(1800);
    }

    @Test
    void blankContentYieldsNothing() {
        assertThat(chunker.split("  ")).isEmpty();
        assertThat(chunker.split(null)).isEmpty();
    }
}
