package com.domwil.xrag.adapter.out.git;

import com.domwil.xrag.config.TeamConfig;
import com.domwil.xrag.domain.model.MergeRequestMeta;
import com.domwil.xrag.domain.model.SourceDocument;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le connecteur lit de VRAIS dépôts git (JGit, dans un dossier temporaire) : un « upstream »
 * bare (rôle de GitLab) et un clone de poste dont seules les refs {@code origin/*} sont
 * fraîches après fetch — exactement la situation d'un dev qui fetch sans checkout.
 */
class LocalGitConnectorTest {

    private static final String WEB = "https://gitlab.example.com";

    @TempDir
    Path root;

    private Path upstreamDir;
    private Git clone;
    private PersonIdent alice;
    private PersonIdent bob;
    private long clock = Instant.parse("2026-03-01T08:00:00Z").getEpochSecond();

    @BeforeEach
    void setUpRepositories() throws Exception {
        alice = new PersonIdent("Alice Martin", "alice.martin@example.com");
        bob = new PersonIdent("Bob", "bob@example.com");
        upstreamDir = root.resolve("upstream").resolve("fps-pos.git");
        // Upstream bare (rôle de GitLab) amorcé par un dépôt de travail : JGit ne clone pas un bare vide.
        try (Git seed = seedBare(upstreamDir, root.resolve("seed-pos"))) {
            commitFile(seed, "README.md", "# fps-pos\n", "PASS-1 initialisation du projet", alice);
            commitFile(seed, "src/main/java/com/fps/pos/Order.java",
                    "package com.fps.pos;\npublic class Order {}\n", "PASS-1 entité Order", alice);
            seed.push().add("main").call();
        }
        // Le clone du poste du dev : GIT_REPOS_DIR/fps-pos (worktree), origin = upstream.
        Path cloneDir = root.resolve("repos").resolve("fps-pos");
        Files.createDirectories(cloneDir.getParent());
        clone = noSigning(Git.cloneRepository().setURI(upstreamDir.toUri().toString())
                .setDirectory(cloneDir.toFile()).call());
    }

    private LocalGitConnector connector() {
        return new LocalGitConnector(new TeamConfig.Git(root.resolve("repos").toString(), List.of(),
                List.of("main"), "origin", WEB, 64, null), List.of("PASS"));
    }

    // ---- code -------------------------------------------------------------------------

    @Test
    void indexeLeCodeDeLaBrancheDistanteAvecLeBlobCommeVersion() throws Exception {
        List<SourceDocument> docs = connector().fetchChangedSince(null);

        assertThat(docs).extracting(SourceDocument::path)
                .containsExactlyInAnyOrder("fps-pos@main/README.md", "fps-pos@main/src/main/java/com/fps/pos/Order.java");
        SourceDocument order = docs.stream().filter(d -> d.path().endsWith("Order.java")).findFirst().orElseThrow();
        assertThat(order.source()).isEqualTo("gitlab-code");
        assertThat(order.project()).isEqualTo("fps-pos"); // slug depuis l'URL du remote (…/upstream/fps-pos.git)
        assertThat(order.title()).isEqualTo("src/main/java/com/fps/pos/Order.java");
        assertThat(order.content()).contains("public class Order");
        assertThat(order.url()).isEqualTo(WEB + "/fps-pos/-/blob/main/src/main/java/com/fps/pos/Order.java");
        assertThat(order.version()).matches("[0-9a-f]{40}"); // id du blob
        assertThat(order.metadata()).containsEntry("transport", "git-local").containsEntry("branch", "main");

        // Un nouveau commit ailleurs ne change PAS la version des fichiers non touchés…
        try (Git author = authoringClone()) {
            commitFile(author, "docs/notes.md", "notes\n", "PASS-2 notes", bob);
            author.push().add("main").call();
        }
        clone.fetch().call(); // …et la branche locale « main » du clone reste en arrière : seule origin/main bouge

        List<SourceDocument> after = connector().fetchChangedSince(Instant.now());
        assertThat(after).extracting(SourceDocument::path).contains("fps-pos@main/docs/notes.md");
        SourceDocument orderAfter = after.stream().filter(d -> d.path().endsWith("Order.java")).findFirst().orElseThrow();
        assertThat(orderAfter.version()).isEqualTo(order.version());
    }

    @Test
    void ignoreLesBinairesLesGrosFichiersEtLesExtensionsNonIndexeesEtNormaliseLeTexte() throws Exception {
        try (Git author = authoringClone()) {
            Path workTree = author.getRepository().getWorkTree().toPath();
            Files.write(workTree.resolve("logo.json"), new byte[]{'{', 0, 1, '}'});
            Files.write(workTree.resolve("legacy.properties"), "clé=sécurité\r\n".getBytes(StandardCharsets.ISO_8859_1));
            Files.write(workTree.resolve("bom.md"), new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'o', 'k'});
            commitFile(author, "big.sql", "x".repeat(70 * 1024), "PASS-3 gros script", alice);
            commitFile(author, "image.png", "png", "PASS-3 image", alice);
            author.add().addFilepattern(".").call();
            author.commit().setMessage("PASS-3 fichiers divers").setAuthor(alice).setCommitter(alice).call();
            author.push().add("main").call();
        }
        clone.fetch().call();

        List<SourceDocument> docs = connector().fetchChangedSince(null);

        assertThat(docs).extracting(SourceDocument::path)
                .noneMatch(p -> p.endsWith("big.sql") || p.endsWith("image.png") || p.endsWith("logo.json"));
        assertThat(docs.stream().filter(d -> d.path().endsWith("legacy.properties")).findFirst().orElseThrow().content())
                .isEqualTo("clé=sécurité\n"); // ISO-8859-1 récupéré, CRLF -> LF
        assertThat(docs.stream().filter(d -> d.path().endsWith("bom.md")).findFirst().orElseThrow().content())
                .isEqualTo("ok");
    }

    // ---- MRs --------------------------------------------------------------------------

    @Test
    void reconstitueUneMrFusionneeDepuisLeCommitDeFusionGitLab() throws Exception {
        RevCommit first;
        RevCommit merge;
        try (Git author = authoringClone()) {
            author.checkout().setCreateBranch(true).setName("feature/PASS-42-suivi-commande").call();
            first = commitFile(author, "src/main/java/com/fps/pos/Tracking.java",
                    "package com.fps.pos;\npublic class Tracking {}\n", "PASS-42 ébauche du suivi", bob);
            commitFile(author, "src/main/java/com/fps/pos/Tracking.java",
                    "package com.fps.pos;\npublic class Tracking { int step; }\n", "pass-42 étape de suivi", bob);
            author.checkout().setName("main").call();
            author.merge().include(author.getRepository().resolve("feature/PASS-42-suivi-commande"))
                    .setFastForward(MergeCommand.FastForwardMode.NO_FF).setCommit(true)
                    .setMessage("""
                            Merge branch 'feature/PASS-42-suivi-commande' into 'main'

                            PASS-42 Suivi de commande

                            Closes PASS-42

                            See merge request passerelle/fps-pos!153""")
                    .call();
            merge = author.log().setMaxCount(1).call().iterator().next();
            author.push().add("main").call();
        }
        clone.fetch().call();

        List<MergeRequestMeta> mrs = connector().fetchUpdatedAfter(null);

        MergeRequestMeta mr = mrs.stream().filter(m -> m.iid() == 153).findFirst().orElseThrow();
        assertThat(mr.id()).isEqualTo("git:fps-pos:153");
        assertThat(mr.project()).isEqualTo("fps-pos");
        assertThat(mr.state()).isEqualTo("merged");
        assertThat(mr.title()).isEqualTo("PASS-42 Suivi de commande");
        assertThat(mr.sourceBranch()).isEqualTo("feature/PASS-42-suivi-commande");
        assertThat(mr.targetBranch()).isEqualTo("main");
        assertThat(mr.author()).isEqualTo("bob"); // auteur des commits de la branche (email), pas du merge
        assertThat(mr.changedFiles()).containsExactly("src/main/java/com/fps/pos/Tracking.java");
        assertThat(mr.description()).contains("Closes PASS-42").contains("Commits (2)")
                .contains("PASS-42 ébauche du suivi").contains("Clés Jira : PASS-42");
        assertThat(mr.createdAt()).isEqualTo(first.getAuthorIdent().getWhenAsInstant());
        assertThat(mr.mergedAt()).isEqualTo(merge.getCommitterIdent().getWhenAsInstant());
        assertThat(mr.updatedAt()).isEqualTo(mr.mergedAt());
        assertThat(mr.webUrl()).isEqualTo(WEB + "/passerelle/fps-pos/-/merge_requests/153"); // chemin cité par GitLab
        assertThat(mr.reference()).isEqualTo("!153");

        // Les commits de la branche fusionnée ne sont PAS refabriqués en MR synthétique
        assertThat(mrs).noneMatch(m -> m.id().startsWith("git:fps-pos:commits:") && m.title().contains("suivi"));
    }

    @Test
    void reconnaitUnCommitDeSquashPortantLaReferenceDeLaMr() throws Exception {
        try (Git author = authoringClone()) {
            commitFile(author, "Export.java", "class Export {}\n",
                    "Export CSV des factures (passerelle/fps-pos!160)\n\nCloses PASS-8", bob);
            author.push().add("main").call();
        }
        clone.fetch().call();

        List<MergeRequestMeta> mrs = connector().fetchUpdatedAfter(null);

        MergeRequestMeta squash = mrs.stream().filter(m -> m.iid() == 160).findFirst().orElseThrow();
        assertThat(squash.id()).isEqualTo("git:fps-pos:160");
        assertThat(squash.title()).isEqualTo("Export CSV des factures");
        assertThat(squash.description()).contains("Closes PASS-8").contains("Clés Jira : PASS-8").contains("squash");
        assertThat(squash.changedFiles()).containsExactly("Export.java");
        assertThat(squash.webUrl()).isEqualTo(WEB + "/passerelle/fps-pos/-/merge_requests/160");
    }

    @Test
    void regroupeLesCommitsDirectsParCleJiraEnMrsSynthetiques() throws Exception {
        // Historique initial : 2 commits PASS-1 (une série) ; on ajoute PASS-7, pass-7, sans clé, PASS-7
        RevCommit firstOfSeries;
        try (Git author = authoringClone()) {
            firstOfSeries = commitFile(author, "a.md", "a\n", "PASS-7 début export", alice);
            commitFile(author, "b.md", "b\n", "pass-7 suite export\n\nDétail de la suite.", alice);
            commitFile(author, "c.md", "c\n", "chore: formatage sans clé", bob);
            commitFile(author, "d.md", "d\n", "PASS-7 fin export", bob);
            author.push().add("main").call();
        }
        clone.fetch().call();

        List<MergeRequestMeta> mrs = connector().fetchUpdatedAfter(null);

        List<MergeRequestMeta> synthetic = mrs.stream().filter(m -> m.id().startsWith("git:fps-pos:commits:")).toList();
        assertThat(synthetic).extracting(MergeRequestMeta::title)
                .containsExactly("PASS-7 fin export", "PASS-7 début export", "PASS-1 initialisation du projet");
        MergeRequestMeta export = synthetic.get(1);
        assertThat(export.id()).isEqualTo("git:fps-pos:commits:" + firstOfSeries.name()); // ancré sur le 1er commit
        assertThat(export.iid()).isZero();
        assertThat(export.reference()).isEqualTo(export.id());
        assertThat(export.state()).isEqualTo("merged");
        assertThat(export.targetBranch()).isEqualTo("main");
        assertThat(export.changedFiles()).containsExactlyInAnyOrder("a.md", "b.md");
        assertThat(export.description()).contains("Commits (2) sur main").contains("Clés Jira : PASS-7");
        assertThat(export.author()).isEqualTo("alice.martin");
        // le commit sans clé Jira n'est pas une MR
        assertThat(mrs).extracting(MergeRequestMeta::title).noneMatch(t -> t.contains("formatage"));

        // La série qui s'allonge garde son id : une nuit plus tard, un commit PASS-7 contigu
        try (Git author = authoringClone()) {
            commitFile(author, "e.md", "e\n", "PASS-7 vraiment fin", bob);
            author.push().add("main").call();
        }
        clone.fetch().call();
        List<MergeRequestMeta> later = connector().fetchUpdatedAfter(null);
        MergeRequestMeta grown = later.stream().filter(m -> m.title().equals("PASS-7 fin export")).findFirst().orElseThrow();
        assertThat(grown.changedFiles()).containsExactlyInAnyOrder("d.md", "e.md");
        assertThat(later).filteredOn(m -> m.description().contains("Clés Jira : PASS-7")).hasSize(2);
    }

    @Test
    void lesBranchesDistantesNonFusionneesSontDesMrsOuvertesPuisDisparaissent() throws Exception {
        try (Git author = authoringClone()) {
            author.checkout().setCreateBranch(true).setName("feature/PASS-9-refonte-tva").call();
            commitFile(author, "Vat.java", "class Vat {}\n", "PASS-9 refonte TVA", bob);
            author.push().add("feature/PASS-9-refonte-tva").call();
            author.checkout().setCreateBranch(true).setName("renovate/spring-boot").call();
            commitFile(author, "pom.xml", "<project/>\n", "Update spring boot", new PersonIdent("renovate[bot]", "bot@renovate"));
            author.push().add("renovate/spring-boot").call();
        }
        clone.fetch().call();

        LocalGitConnector connector = connector();
        List<MergeRequestMeta> mrs = connector.fetchUpdatedAfter(Instant.parse("2030-01-01T00:00:00Z"));

        assertThat(mrs).filteredOn(m -> m.state().equals("opened")).hasSize(1); // renovate/* exclue
        MergeRequestMeta open = mrs.stream().filter(m -> m.state().equals("opened")).findFirst().orElseThrow();
        assertThat(open.id()).isEqualTo("git-branch:fps-pos:feature/PASS-9-refonte-tva");
        assertThat(open.title()).isEqualTo("PASS-9 refonte TVA");
        assertThat(open.sourceBranch()).isEqualTo("feature/PASS-9-refonte-tva");
        assertThat(open.targetBranch()).isEqualTo("main");
        assertThat(open.changedFiles()).containsExactly("Vat.java");
        assertThat(open.mergedAt()).isNull();
        assertThat(open.webUrl()).isEqualTo(WEB + "/fps-pos/-/tree/feature/PASS-9-refonte-tva");
        // instantané autoritaire de la récolte, même avec un curseur dans le futur
        assertThat(connector.currentlyOpenIds()).contains(Set.of("git-branch:fps-pos:feature/PASS-9-refonte-tva"));

        // Fusion fast-forward + suppression de la branche : plus de MR ouverte, la série PASS-9 devient une MR synthétique
        try (Git author = authoringClone()) {
            author.merge().include(author.getRepository().resolve("origin/feature/PASS-9-refonte-tva"))
                    .setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call();
            author.push().add("main").call();
            author.push().setRefSpecs(new RefSpec(":refs/heads/feature/PASS-9-refonte-tva")).call();
        }
        clone.fetch().setRemoveDeletedRefs(true).call();

        List<MergeRequestMeta> after = connector.fetchUpdatedAfter(null);
        assertThat(after).noneMatch(m -> m.state().equals("opened"));
        assertThat(after).anyMatch(m -> m.id().startsWith("git:fps-pos:commits:") && m.title().equals("PASS-9 refonte TVA"));
        assertThat(connector.currentlyOpenIds()).contains(Set.of());
    }

    @Test
    void uneBrancheDejaFusionneeParSquashDontLeNomEstCiteNestPasOuverte() throws Exception {
        try (Git author = authoringClone()) {
            author.checkout().setCreateBranch(true).setName("feature/PASS-5-menu").call();
            commitFile(author, "Menu.java", "class Menu {}\n", "PASS-5 menu", bob);
            author.push().add("feature/PASS-5-menu").call();
            // GitLab squash + merge commit : le contenu est réappliqué en UN commit sur une branche
            // temporaire, puis fusionné — le tip de la branche d'origine n'est PAS ancêtre de main
            author.checkout().setName("main").call();
            author.checkout().setCreateBranch(true).setName("tmp-squashed").call();
            commitFile(author, "Menu.java", "class Menu {}\n", "PASS-5 Menu (squash)", bob);
            author.checkout().setName("main").call();
            commitFile(author, "other.md", "o\n", "PASS-6 autre", alice);
            author.merge().include(author.getRepository().resolve("tmp-squashed"))
                    .setFastForward(MergeCommand.FastForwardMode.NO_FF).setCommit(true)
                    .setMessage("Merge branch 'feature/PASS-5-menu' into 'main'\n\nPASS-5 Menu\n\nSee merge request grp/fps-pos!170")
                    .call();
            author.push().add("main").call();
        }
        clone.fetch().call();

        LocalGitConnector connector = connector();
        List<MergeRequestMeta> mrs = connector.fetchUpdatedAfter(null);

        assertThat(mrs).noneMatch(m -> m.state().equals("opened") && m.sourceBranch().equals("feature/PASS-5-menu"));
        assertThat(mrs).anyMatch(m -> m.iid() == 170);
        // l'instantané autoritaire est celui de la récolte : la branche n'y figure pas non plus,
        // sinon sa ligne « opened » d'une sync précédente ne serait jamais retirée
        assertThat(connector.currentlyOpenIds()).contains(Set.of());
    }

    @Test
    void decouvreClonesEtDepotsBareEtIgnoreLeReste() throws Exception {
        Files.createDirectories(root.resolve("repos").resolve("pas-un-depot").resolve("src"));
        // dépôt bare à profondeur 2 (disposition groupe/projet), sans remote : slug = nom du dossier
        Path bare = root.resolve("repos").resolve("outils").resolve("fps-kds.git");
        try (Git seed = seedBare(bare, root.resolve("seed-kds"))) {
            commitFile(seed, "Kds.java", "class Kds {}\n", "PASS-11 kds", alice);
            seed.push().add("main").call();
        }
        // le bare n'a pas de remote : slug = nom du dossier
        try (org.eclipse.jgit.lib.Repository kdsRepo = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
                .setGitDir(bare.toFile()).setBare().build()) {
            StoredConfig config = kdsRepo.getConfig();
            config.unsetSection("remote", "origin");
            config.save();
        }
        Files.createSymbolicLink(root.resolve("repos").resolve("lien"), root.resolve("upstream"));

        List<SourceDocument> docs = connector().fetchChangedSince(null);

        assertThat(docs).extracting(SourceDocument::path)
                .contains("fps-kds@main/Kds.java", "fps-pos@main/README.md")
                .noneMatch(p -> p.contains("pas-un-depot") || p.contains("lien"));
        SourceDocument kds = docs.stream().filter(d -> d.project().equals("fps-kds")).findFirst().orElseThrow();
        assertThat(kds.url()).isEqualTo(WEB + "/outils/fps-kds/-/blob/main/Kds.java");
    }

    @Test
    void racineAbsenteOuSansDepotEstUneErreurExplicite() throws Exception {
        var absent = new LocalGitConnector(new TeamConfig.Git(root.resolve("nulle-part").toString(),
                List.of(), List.of("main"), null, null, null, null));
        assertThatThrownBy(() -> absent.fetchChangedSince(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GIT_REPOS_DIR");

        Files.createDirectories(root.resolve("vide"));
        var empty = new LocalGitConnector(new TeamConfig.Git(root.resolve("vide").toString(),
                List.of(), List.of("main"), null, null, null, null));
        assertThatThrownBy(() -> empty.fetchUpdatedAfter(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Aucun dépôt git");
        assertThat(empty.currentlyOpenIds()).isEmpty(); // jamais de réconciliation sur un instantané absent
    }

    @Test
    void unCheminExpliciteHorsRacineOuAbsentEstUneErreurFailClosed() {
        var outside = new LocalGitConnector(new TeamConfig.Git(root.resolve("repos").toString(),
                List.of("../upstream/fps-pos.git", "fps-pos"), List.of("main"), null, null, null, null));
        assertThatThrownBy(() -> outside.fetchChangedSince(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hors de la racine");

        var absent = new LocalGitConnector(new TeamConfig.Git(root.resolve("repos").toString(),
                List.of("fps-pos", "fps-inexistant"), List.of("main"), null, null, null, null));
        assertThatThrownBy(() -> absent.fetchUpdatedAfter(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fps-inexistant");
        assertThat(absent.currentlyOpenIds()).isEmpty(); // récolte en échec : aucun instantané

        var explicit = new LocalGitConnector(new TeamConfig.Git(root.resolve("repos").toString(),
                List.of("fps-pos"), List.of("main"), null, null, null, null));
        assertThat(explicit.fetchChangedSince(null)).hasSize(2);
    }

    @Test
    void deuxDepotsDeMemeSlugSontRefuses() throws Exception {
        Path fork = root.resolve("repos").resolve("fork").resolve("fps-pos");
        Files.createDirectories(fork.getParent());
        noSigning(Git.cloneRepository().setURI(upstreamDir.toUri().toString()).setDirectory(fork.toFile()).call()).close();

        assertThatThrownBy(() -> connector().fetchChangedSince(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("même projet « fps-pos");
    }

    @Test
    void sansVoieBranchesOuvertesAucunInstantaneDoncAucuneReconciliation() throws Exception {
        try (Git author = authoringClone()) {
            author.checkout().setCreateBranch(true).setName("feature/PASS-77").call();
            commitFile(author, "S.java", "class S {}\n", "PASS-77 s", bob);
            author.push().add("feature/PASS-77").call();
        }
        clone.fetch().call();
        var connector = new LocalGitConnector(new TeamConfig.Git(root.resolve("repos").toString(), List.of(),
                List.of("main"), "origin", WEB, 64,
                new TeamConfig.GitMergeRequests(true, true, false, null, null, null, null, null)), List.of("PASS"));

        List<MergeRequestMeta> mrs = connector.fetchUpdatedAfter(null);

        assertThat(mrs).noneMatch(m -> m.state().equals("opened"));
        assertThat(connector.currentlyOpenIds()).isEmpty(); // pas un instantané : la table est laissée telle quelle
    }

    @Test
    void uneBrancheCibleAbsenteDeToutDepotEstUneErreur() {
        var connector = new LocalGitConnector(new TeamConfig.Git(root.resolve("repos").toString(),
                List.of(), List.of("master"), null, null, null, null));

        assertThatThrownBy(() -> connector.fetchChangedSince(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("[master]");
    }

    @Test
    void laSynchronisationDUneCibleDansUneFeatureNestPasUneMrEtNeCouvrePasMain() throws Exception {
        RevCommit pass2;
        try (Git author = authoringClone()) {
            author.checkout().setCreateBranch(true).setName("feature/PASS-30-long").call();
            commitFile(author, "Long.java", "class Long {}\n", "PASS-30 début", bob);
            author.checkout().setName("main").call();
            pass2 = commitFile(author, "Main.java", "class Main {}\n", "PASS-2 direct sur main", alice);
            // le dev synchronise sa feature avec main (git merge main) …
            author.checkout().setName("feature/PASS-30-long").call();
            author.merge().include(author.getRepository().resolve("main"))
                    .setFastForward(MergeCommand.FastForwardMode.NO_FF).setCommit(true)
                    .setMessage("Merge branch 'main' into feature/PASS-30-long").call();
            commitFile(author, "Long.java", "class Long { int x; }\n", "PASS-30 fin", bob);
            // … puis la feature est fusionnée par GitLab
            author.checkout().setName("main").call();
            author.merge().include(author.getRepository().resolve("feature/PASS-30-long"))
                    .setFastForward(MergeCommand.FastForwardMode.NO_FF).setCommit(true)
                    .setMessage("Merge branch 'feature/PASS-30-long' into 'main'\n\nPASS-30 Long\n\nSee merge request grp/fps-pos!200")
                    .call();
            author.push().add("main").call();
        }
        clone.fetch().call();

        List<MergeRequestMeta> mrs = connector().fetchUpdatedAfter(null);

        assertThat(mrs).anyMatch(m -> m.iid() == 200 && m.changedFiles().contains("Long.java"));
        assertThat(mrs).noneMatch(m -> "main".equals(m.sourceBranch())); // la sync main→feature n'est pas une MR
        assertThat(mrs).noneMatch(m -> m.id().contains(":merge:"));
        // le commit direct PASS-2 sur main reste une série synthétique (non « couvert » par la sync)
        assertThat(mrs).anyMatch(m -> m.id().equals("git:fps-pos:commits:" + pass2.name())
                && m.title().equals("PASS-2 direct sur main"));
    }

    @Test
    void unCommitQuiCiteUneMrNestPasSonCommitDeSquash() throws Exception {
        try (Git author = authoringClone()) {
            author.checkout().setCreateBranch(true).setName("feature/PASS-42-x").call();
            commitFile(author, "X.java", "class X {}\n", "PASS-42 x", bob);
            author.checkout().setName("main").call();
            author.merge().include(author.getRepository().resolve("feature/PASS-42-x"))
                    .setFastForward(MergeCommand.FastForwardMode.NO_FF).setCommit(true)
                    .setMessage("Merge branch 'feature/PASS-42-x' into 'main'\n\nPASS-42 Vrai titre\n\nSee merge request grp/fps-pos!153")
                    .call();
            // plus tard, un commit direct CITE la MR (et un revert GitLab la cite aussi)
            commitFile(author, "Y.java", "class Y {}\n", "PASS-43 régression de !153 corrigée", alice);
            commitFile(author, "X.java", "", "Revert \"PASS-42 Vrai titre (grp/fps-pos!153)\"\n\nThis reverts merge request !153", alice);
            author.push().add("main").call();
        }
        clone.fetch().call();

        List<MergeRequestMeta> mrs = connector().fetchUpdatedAfter(null);

        MergeRequestMeta mr = mrs.stream().filter(m -> m.iid() == 153).findFirst().orElseThrow();
        assertThat(mr.title()).isEqualTo("PASS-42 Vrai titre"); // pas préemptée par le commit citant !153
        assertThat(mr.changedFiles()).containsExactly("X.java");
        assertThat(mrs).filteredOn(m -> m.iid() == 153).hasSize(1);
        assertThat(mrs).anyMatch(m -> m.id().startsWith("git:fps-pos:commits:") && m.title().startsWith("PASS-43"));
    }

    // ---- helpers ------------------------------------------------------------------------

    /** Crée un dépôt bare vide et un dépôt de travail (branche main) dont origin le cible. */
    private static Git seedBare(Path bare, Path workDir) throws Exception {
        Files.createDirectories(bare);
        Git.init().setDirectory(bare.toFile()).setBare(true).setInitialBranch("main").call().close();
        Git seed = noSigning(Git.init().setDirectory(workDir.toFile()).setInitialBranch("main").call());
        StoredConfig config = seed.getRepository().getConfig();
        config.setString("remote", "origin", "url", bare.toUri().toString());
        config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
        config.save();
        return seed;
    }

    private Git authoringClone() throws Exception {
        Path dir = root.resolve("authoring-" + (clock++));
        return noSigning(Git.cloneRepository().setURI(upstreamDir.toUri().toString()).setDirectory(dir.toFile()).call());
    }

    /** La config git globale du poste peut imposer commit.gpgsign : les commits de test ne signent jamais. */
    private static Git noSigning(Git git) throws Exception {
        StoredConfig config = git.getRepository().getConfig();
        config.setBoolean("commit", null, "gpgsign", false);
        config.save();
        return git;
    }

    private RevCommit commitFile(Git git, String path, String content, String message, PersonIdent who) throws Exception {
        Path file = git.getRepository().getWorkTree().toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        PersonIdent stamped = new PersonIdent(who, Instant.ofEpochSecond(clock += 60), ZoneOffset.UTC);
        return git.commit().setMessage(message).setAuthor(stamped).setCommitter(stamped).setSign(false).call();
    }
}
