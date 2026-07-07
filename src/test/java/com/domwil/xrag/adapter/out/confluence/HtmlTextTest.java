package com.domwil.xrag.adapter.out.confluence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlTextTest {

    @Test
    void convertsConfluenceStorageFormatToPlainText() {
        String html = """
                <h1>Easy Loc</h1>
                <p>Service de <strong>location</strong> &amp; facturation.</p>
                <ul><li>Publie sur le topic orders</li><li>Appelle l&#39;API Epsilon</li></ul>
                <script>alert("ignore");</script>
                """;

        String text = HtmlText.toText(html);

        assertThat(text)
                .contains("Easy Loc")
                .contains("Service de location & facturation.")
                .contains("Publie sur le topic orders")
                .contains("Appelle l'API Epsilon")
                .doesNotContain("<")
                .doesNotContain("alert");
    }

    @Test
    void toleratesNullAndBlank() {
        assertThat(HtmlText.toText(null)).isEmpty();
        assertThat(HtmlText.toText("  ")).isEmpty();
    }
}
