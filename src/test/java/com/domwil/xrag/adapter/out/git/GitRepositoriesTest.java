package com.domwil.xrag.adapter.out.git;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitRepositoriesTest {

    @Test
    void deduitSlugCheminEtOrigineDesUrlsDeRemote() {
        var scp = GitRepositories.parseRemoteUrl("git@gitlab.example.com:passerelle/backend/fps-pos.git").orElseThrow();
        assertThat(scp.slug()).isEqualTo("fps-pos");
        assertThat(scp.path()).isEqualTo("passerelle/backend/fps-pos");
        assertThat(scp.webOrigin()).isEqualTo("https://gitlab.example.com");

        var https = GitRepositories.parseRemoteUrl("https://oauth2:secret@gitlab.example.com/passerelle/fps-kds.git").orElseThrow();
        assertThat(https.slug()).isEqualTo("fps-kds");
        assertThat(https.path()).isEqualTo("passerelle/fps-kds");
        assertThat(https.webOrigin()).isEqualTo("https://gitlab.example.com");

        var ssh = GitRepositories.parseRemoteUrl("ssh://git@gitlab.example.com:2222/passerelle/fps-web").orElseThrow();
        assertThat(ssh.slug()).isEqualTo("fps-web");
        assertThat(ssh.path()).isEqualTo("passerelle/fps-web");
        assertThat(ssh.webOrigin()).isEqualTo("https://gitlab.example.com");

        var http = GitRepositories.parseRemoteUrl("http://gitlab.interne/grp/proj/").orElseThrow();
        assertThat(http.webOrigin()).isEqualTo("http://gitlab.interne");
        assertThat(http.path()).isEqualTo("grp/proj");

        var file = GitRepositories.parseRemoteUrl("file:///home/dev/upstream/fps-pos.git").orElseThrow();
        assertThat(file.slug()).isEqualTo("fps-pos");
        assertThat(file.path()).isNull();
        assertThat(file.webOrigin()).isNull();

        assertThat(GitRepositories.parseRemoteUrl("")).isEmpty();
        assertThat(GitRepositories.parseRemoteUrl("git@host:../evasion.git")).isEmpty();
    }
}
