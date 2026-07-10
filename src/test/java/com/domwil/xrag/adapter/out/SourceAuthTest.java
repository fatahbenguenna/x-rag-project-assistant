package com.domwil.xrag.adapter.out;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SourceAuthTest {

    @Test
    void bearerParDefaut() {
        var auth = SourceAuth.resolve("mon-pat", "", "");
        assertThat(auth.mode()).isEqualTo("bearer");
        assertThat(auth.headerName()).isEqualTo("Authorization");
        assertThat(auth.headerValue()).isEqualTo("Bearer mon-pat");
    }

    @Test
    void basicQuandUserDefini() {
        var auth = SourceAuth.resolve("api-token", "svc-xrag", "");
        assertThat(auth.mode()).isEqualTo("basic");
        assertThat(auth.headerName()).isEqualTo("Authorization");
        String decoded = new String(
                Base64.getDecoder().decode(auth.headerValue().substring("Basic ".length())),
                StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("svc-xrag:api-token");
    }

    @Test
    void cookiePrioritaireSurBasicEtBearer() {
        var auth = SourceAuth.resolve("token", "user", "confluence_cookie=abc; JSESSIONID=def");
        assertThat(auth.mode()).isEqualTo("cookie");
        assertThat(auth.headerName()).isEqualTo("Cookie");
        assertThat(auth.headerValue()).isEqualTo("confluence_cookie=abc; JSESSIONID=def");
    }

    @Test
    void cookieBlancIgnore() {
        var auth = SourceAuth.resolve("token", "", "   ");
        assertThat(auth.mode()).isEqualTo("bearer");
    }
}
