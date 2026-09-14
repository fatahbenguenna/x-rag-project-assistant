package com.domwil.xrag.adapter.out.git;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MergeCommitMessageParserTest {

    @Test
    void gitlabTemplateParDefaut() {
        var parsed = MergeCommitMessageParser.parse("""
                Merge branch 'feature/PASS-123-suivi-commande' into 'develop'

                PASS-123 Suivi de commande

                Closes PASS-123

                See merge request passerelle/fps/fps-pos!153
                """).orElseThrow();

        assertThat(parsed.hosting()).isEqualTo("gitlab");
        assertThat(parsed.iid()).isEqualTo(153);
        assertThat(parsed.reference()).isEqualTo("passerelle/fps/fps-pos");
        assertThat(parsed.sourceBranch()).isEqualTo("feature/PASS-123-suivi-commande");
        assertThat(parsed.targetBranch()).isEqualTo("develop");
        assertThat(parsed.title()).isEqualTo("PASS-123 Suivi de commande");
        assertThat(parsed.description()).contains("Closes PASS-123").contains("See merge request")
                .doesNotContain("Suivi de commande");
    }

    @Test
    void gitlabAncienFormatSansCheminEtSansTitre() {
        var parsed = MergeCommitMessageParser.parse(
                "Merge branch 'fix-npe' into 'main'\n\nSee merge request !42\n").orElseThrow();

        assertThat(parsed.iid()).isEqualTo(42);
        assertThat(parsed.reference()).isNull();
        assertThat(parsed.title()).isNull(); // repli sur le nom de branche côté reconstitution
    }

    @Test
    void github() {
        var parsed = MergeCommitMessageParser.parse(
                "Merge pull request #12 from acme/feature/PASS-7-export\n\nPASS-7 Export CSV").orElseThrow();

        assertThat(parsed.hosting()).isEqualTo("github");
        assertThat(parsed.iid()).isEqualTo(12);
        assertThat(parsed.sourceBranch()).isEqualTo("feature/PASS-7-export");
        assertThat(parsed.title()).isEqualTo("PASS-7 Export CSV");
    }

    @Test
    void bitbucketEtAzure() {
        var bitbucket = MergeCommitMessageParser.parse(
                "Merged in feature/PASS-9 (pull request #33)\n\nPASS-9 Refonte TVA\n\nApproved-by: Bob").orElseThrow();
        assertThat(bitbucket.hosting()).isEqualTo("bitbucket");
        assertThat(bitbucket.iid()).isEqualTo(33);
        assertThat(bitbucket.title()).isEqualTo("PASS-9 Refonte TVA");

        var azure = MergeCommitMessageParser.parse("Merged PR 77: PASS-10 Nouveau menu").orElseThrow();
        assertThat(azure.hosting()).isEqualTo("azure");
        assertThat(azure.iid()).isEqualTo(77);
        assertThat(azure.title()).isEqualTo("PASS-10 Nouveau menu");
    }

    @Test
    void gitNatifSansNumero() {
        var parsed = MergeCommitMessageParser.parse("Merge branch 'hotfix/PASS-5' into develop").orElseThrow();

        assertThat(parsed.hosting()).isEqualTo("git");
        assertThat(parsed.iid()).isZero();
        assertThat(parsed.sourceBranch()).isEqualTo("hotfix/PASS-5");
        assertThat(parsed.targetBranch()).isEqualTo("develop");
        assertThat(parsed.title()).isNull();
    }

    @Test
    void lesFusionsDeSynchronisationNeSontPasDesMrs() {
        assertThat(MergeCommitMessageParser.parse("Merge branch 'main' of https://gitlab.example.com/g/p")).isEmpty();
        assertThat(MergeCommitMessageParser.parse("Merge remote-tracking branch 'origin/develop' into feature/x")).isEmpty();
        assertThat(MergeCommitMessageParser.parse("Merge branch 'origin/main' into 'main'")).isEmpty();
        assertThat(MergeCommitMessageParser.parse("Merge branch 'main' into 'main'")).isEmpty();
        assertThat(MergeCommitMessageParser.parse("PASS-1 commit ordinaire")).isEmpty();
        assertThat(MergeCommitMessageParser.parse("")).isEmpty();
        assertThat(MergeCommitMessageParser.parse(null)).isEmpty();
    }

    @Test
    void tolereCrlfGuillemetsAbsentsEtReferenceAilleurs() {
        var crlf = MergeCommitMessageParser.parse(
                "Merge branch 'fix/PASS-3' into 'main'\r\n\r\nPASS-3 Correctif\r\n\r\nSee merge request grp/proj!8\r\n").orElseThrow();
        assertThat(crlf.iid()).isEqualTo(8);
        assertThat(crlf.title()).isEqualTo("PASS-3 Correctif");

        var noQuotes = MergeCommitMessageParser.parse("Merge branch feat/x into release/2.4").orElseThrow();
        assertThat(noQuotes.sourceBranch()).isEqualTo("feat/x");
        assertThat(noQuotes.targetBranch()).isEqualTo("release/2.4");

        var custom = MergeCommitMessageParser.parse(
                "Merge feat/PASS-4 (grp/sub/proj!99)\n\nDescription libre").orElseThrow();
        assertThat(custom.hosting()).isEqualTo("gitlab");
        assertThat(custom.iid()).isEqualTo(99);
        assertThat(custom.reference()).isEqualTo("grp/sub/proj");
        assertThat(custom.title()).isEqualTo("Merge feat/PASS-4");

        var url = MergeCommitMessageParser.parse(
                "Merge branch 'a' into 'main'\n\nhttps://gitlab.example.com/grp/proj/-/merge_requests/101").orElseThrow();
        assertThat(url.iid()).isEqualTo(101);
    }

    @Test
    void referenceDeFusionParOrdreDeFiabilite() {
        // le trailer prime sur une MR citée dans la description
        assertThat(MergeCommitMessageParser.reference(
                "Merge branch 'a' into 'main'\n\nCorrige la régression de grp/proj!7\n\nSee merge request grp/proj!12"))
                .contains(new MergeCommitMessageParser.MergeReference(12, "grp/proj"));
        assertThat(MergeCommitMessageParser.reference("Titre (grp/proj!12)")).contains(
                new MergeCommitMessageParser.MergeReference(12, "grp/proj"));
        assertThat(MergeCommitMessageParser.reference("See merge request !12")).contains(
                new MergeCommitMessageParser.MergeReference(12, null));
        assertThat(MergeCommitMessageParser.reference("Titre !12")).isEmpty();          // « !12 » nu : une citation
        assertThat(MergeCommitMessageParser.reference("Wow!2024 c'est top")).isEmpty(); // pas une référence
        assertThat(MergeCommitMessageParser.reference("PASS-1 commit ordinaire")).isEmpty();
    }

    @Test
    void referenceDeSquashSeulementDansLesFormesEcritesParGitLab() {
        assertThat(MergeCommitMessageParser.squashReference("Titre (grp/proj!12)\n\nDescription")).contains(
                new MergeCommitMessageParser.MergeReference(12, "grp/proj"));
        assertThat(MergeCommitMessageParser.squashReference("Titre\n\nSee merge request grp/proj!12")).contains(
                new MergeCommitMessageParser.MergeReference(12, "grp/proj"));
        assertThat(MergeCommitMessageParser.squashReference("PASS-43 régression de !153 corrigée")).isEmpty();
        assertThat(MergeCommitMessageParser.squashReference("PASS-43 corrige grp/proj!153")).isEmpty();
        assertThat(MergeCommitMessageParser.squashReference("Revert \"Titre (grp/proj!12)\"\n\nThis reverts merge request !12")).isEmpty();
        assertThat(MergeCommitMessageParser.squashReference("Description\n\nTitre (grp/proj!12)")).isEmpty(); // pas sur le sujet
    }

    @Test
    void suffixeDeTitreRetireEnTempsLineaire() {
        assertThat(MergeCommitMessageParser.stripReferenceSuffix("Titre (grp/proj!12)")).isEqualTo("Titre");
        assertThat(MergeCommitMessageParser.stripReferenceSuffix("Titre grp/proj!12")).isEqualTo("Titre");
        assertThat(MergeCommitMessageParser.stripReferenceSuffix("Titre")).isEqualTo("Titre");
        assertThat(MergeCommitMessageParser.stripReferenceSuffix("Wow!2024 c'est top")).isEqualTo("Wow!2024 c'est top");
        assertThat(MergeCommitMessageParser.stripReferenceSuffix("(grp/proj!12)")).isEqualTo("(grp/proj!12)");
        String longSubject = "x".repeat(200_000) + " (grp/proj!12)";
        assertThat(MergeCommitMessageParser.stripReferenceSuffix(longSubject)).hasSize(200_000);
    }

    @Test
    void unTitreCommencantParUnVerbeDeClotureResteUnTitre() {
        // « Resolve "<issue>" » est le titre par défaut d'une MR GitLab créée depuis une issue
        var resolve = MergeCommitMessageParser.parse(
                "Merge branch '12-panier-vide' into 'main'\n\nResolve \"Panier vide\"\n\nCloses #12\n\nSee merge request grp/proj!6")
                .orElseThrow();
        assertThat(resolve.title()).isEqualTo("Resolve \"Panier vide\"");
        assertThat(resolve.description()).contains("Closes #12").doesNotContain("Panier vide");

        var fix = MergeCommitMessageParser.parse(
                "Merge branch 'f' into 'main'\n\nFix NPE on checkout\n\nFixes PASS-3\n\nSee merge request grp/proj!7").orElseThrow();
        assertThat(fix.title()).isEqualTo("Fix NPE on checkout");
        assertThat(fix.description()).contains("Fixes PASS-3");

        var implement = MergeCommitMessageParser.parse(
                "Merge branch 'f' into 'main'\n\nImplements the new menu\n\nSee merge request grp/proj!8").orElseThrow();
        assertThat(implement.title()).isEqualTo("Implements the new menu");

        // les vrais trailers restent des trailers
        var trailers = MergeCommitMessageParser.parse(
                "Merge branch 'f' into 'main'\n\nCloses PASS-9, PASS-10\nCo-authored-by: Bob <b@x>\nChangelog: fixed\n\nSee merge request !9")
                .orElseThrow();
        assertThat(trailers.title()).isNull();
    }

    @Test
    void humaniseLesNomsDeBranche() {
        assertThat(MergeCommitMessageParser.humanizeBranch("feature/PASS-12-suivi_commande"))
                .isEqualTo("PASS-12 suivi commande");
        assertThat(MergeCommitMessageParser.humanizeBranch("hotfix")).isEqualTo("hotfix");
        assertThat(MergeCommitMessageParser.humanizeBranch(null)).isEmpty();
    }
}
