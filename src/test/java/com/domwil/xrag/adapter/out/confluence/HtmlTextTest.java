package com.domwil.xrag.adapter.out.confluence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlTextTest {

    @Test
    void convertsConfluenceStorageFormatToPlainText() {
        String html = """
                <h1>FPS KDS</h1>
                <p>Service de <strong>location</strong> &amp; facturation.</p>
                <ul><li>Publie sur le topic orders</li><li>Appelle l&#39;API fps-pos</li></ul>
                <script>alert("ignore");</script>
                """;

        String text = HtmlText.toText(html);

        assertThat(text)
                .contains("FPS KDS")
                .contains("Service de location & facturation.")
                .contains("Publie sur le topic orders")
                .contains("Appelle l'API fps-pos")
                .doesNotContain("<")
                .doesNotContain("alert");
    }

    @Test
    void toleratesNullAndBlank() {
        assertThat(HtmlText.toText(null)).isEmpty();
        assertThat(HtmlText.toText("  ")).isEmpty();
    }
}
