package com.domwil.xrag.adapter.out.git;

import com.domwil.xrag.config.TeamConfig;
import com.domwil.xrag.domain.model.MergeRequestMeta;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.MergeRequestConnector;
import com.domwil.xrag.domain.port.SourceConnector;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevWalkException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Adapter « dépôts git locaux » : même rôle que {@code GitLabConnector} (code + MRs) sans
 * aucun appel à l'API GitLab — pour les postes où la politique de sécurité interdit le
 * token/session API alors que {@code git fetch} reste possible. Les miroirs/clones du poste
 * sont montés en lecture seule dans le conteneur ({@code GIT_REPOS_DIR}) et lus via JGit.
 *
 * <p>Même identifiant de source et même schéma de path que l'API GitLab
 * ({@link SourceConnector#CODE_SOURCE}, {@code slug@branche/fichier}) : basculer d'un mode à
 * l'autre ne duplique aucun chunk et conserve les extracteurs. Les MRs sont
 * <b>reconstituées</b> depuis l'historique ({@link GitHistoryMergeRequestReconstructor}) ;
 * les clés Jira des commits alimentent le graphe via le mapper MR existant.
 *
 * <p><b>Fail-closed</b> : un dépôt illisible fait échouer la sync (statut ERREUR visible) plutôt
 * que de produire une récolte partielle — la réconciliation des MRs ouvertes ne doit jamais
 * supprimer sur un instantané incomplet. L'application ne fait jamais de {@code git fetch}
 * (aucun credential dans le conteneur) : la fraîcheur est celle des fetch du poste
 * ({@code scripts/git-fetch-all.sh}).
 */
public class LocalGitConnector implements SourceConnector, MergeRequestConnector {

    public static final String SOURCE = SourceConnector.CODE_SOURCE;

    private static final Logger log = LoggerFactory.getLogger(LocalGitConnector.class);
    private static final Duration STALE_FETCH = Duration.ofHours(26);

    private final TeamConfig.Git config;
    private final Path root;
    private final GitCodeReader codeReader;
    private final GitHistoryMergeRequestReconstructor reconstructor;
    /** Instantané des MRs ouvertes de la dernière récolte COMPLÈTE réussie ; null sinon. */
    private final AtomicReference<Set<String>> lastOpenSnapshot = new AtomicReference<>();

    public LocalGitConnector(TeamConfig.Git config) {
        this(config, List.of());
    }

    /** @param jiraProjects projets Jira configurés (repli quand {@code merge-requests.jira-projects} est vide) */
    public LocalGitConnector(TeamConfig.Git config, List<String> jiraProjects) {
        this.config = config;
        this.root = Path.of(config.root());
        this.codeReader = new GitCodeReader(config.maxFileKb(), config.webBaseUrl());
        var mr = config.mergeRequests();
        this.reconstructor = new GitHistoryMergeRequestReconstructor(new GitHistoryMergeRequestReconstructor.Options(
                mr.fromMergeCommits(), mr.fromLinearCommits(), mr.fromOpenBranches(), mr.openBranchesExclude(),
                mr.jiraProjects().isEmpty() ? jiraProjects : mr.jiraProjects(),
                mr.maxChangedFiles(), mr.maxCommitsListed(), config.remote(), config.webBaseUrl()));
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<SourceDocument> fetchChangedSince(Instant since) {
        var documents = new ArrayList<SourceDocument>();
        streamChangedSince(since, documents::add);
        return documents;
    }

    /**
     * Récolte COMPLÈTE des fichiers en tête de chaque branche cible, quel que soit {@code since} :
     * la version (id de blob) laisse l'ingestion sauter les fichiers inchangés, sans dépendre
     * d'une fenêtre temporelle — un fetch tardif ne crée pas de trou. En flux : rien n'est accumulé.
     */
    @Override
    public void streamChangedSince(Instant since, Consumer<SourceDocument> sink) {
        for (GitRepositories.LocalRepo repo : repositories()) {
            var emitted = new HashSet<String>(); // partagé entre les essais : jamais de document ré-émis
            withRetry(repo, "lecture du code", repository -> {
                for (var head : branchHeads(repository, repo).entrySet()) {
                    int count = codeReader.stream(repository, repo, head.getKey(), head.getValue(), emitted, sink);
                    log.info("Dépôt {} @{} : {} fichier(s) indexable(s)", repo.slug(), head.getKey(), count);
                }
            });
        }
    }

    /**
     * Fail-closed : toute erreur remonte (sync en ERREUR, rien de réconcilié). En cas de succès,
     * l'instantané des MRs ouvertes de TOUS les dépôts est mémorisé pour {@link #currentlyOpenIds()}.
     */
    @Override
    public List<MergeRequestMeta> fetchUpdatedAfter(Instant since) {
        lastOpenSnapshot.set(null);
        Instant effectiveSince = since == null ? null
                : since.minus(config.mergeRequests().rescanDays(), ChronoUnit.DAYS);
        var mergeRequests = new ArrayList<MergeRequestMeta>();
        var openIds = new LinkedHashSet<String>();
        for (GitRepositories.LocalRepo repo : repositories()) {
            var contribution = new AtomicReference<GitHistoryMergeRequestReconstructor.Result>();
            withRetry(repo, "reconstitution des MRs", repository -> contribution.set(
                    reconstructor.reconstruct(repository, repo, branchHeads(repository, repo), effectiveSince)));
            // contribution ajoutée APRÈS succès : un nouvel essai ne duplique rien
            mergeRequests.addAll(contribution.get().mergeRequests());
            openIds.addAll(contribution.get().openIds());
        }
        if (config.mergeRequests().fromOpenBranches()) {
            lastOpenSnapshot.set(Set.copyOf(openIds));
        }
        return mergeRequests;
    }

    /**
     * Instantané autoritaire des MRs ouvertes, tel que produit par la dernière récolte complète
     * réussie ({@link #fetchUpdatedAfter}) — le MÊME que celui dont les lignes viennent d'être
     * upsertées (une branche squash-mergée exclue de la récolte l'est aussi de l'instantané).
     * Vide tant qu'aucune récolte n'a abouti, et vide quand {@code from-open-branches} est
     * désactivé (le connecteur n'énumère alors rien : ce n'est pas un instantané, la table
     * est laissée telle quelle) : jamais de réconciliation sur un état partiel.
     */
    @Override
    public Optional<Set<String>> currentlyOpenIds() {
        return Optional.ofNullable(lastOpenSnapshot.get());
    }

    private interface RepositoryAction {
        void run(Repository repository) throws IOException;
    }

    /**
     * Ouvre le dépôt et exécute l'action ; un objet manquant (pack supprimé par un
     * {@code git gc} concurrent) vaut UNE réouverture, puis l'erreur remonte (fail-closed).
     */
    private void withRetry(GitRepositories.LocalRepo repo, String what, RepositoryAction action) {
        for (int attempt = 1; ; attempt++) {
            MissingObjectException missing;
            try (Repository repository = GitRepositories.open(repo)) {
                action.run(repository);
                return;
            } catch (MissingObjectException e) {
                missing = e;
            } catch (RevWalkException e) {
                // l'itération d'un RevWalk enveloppe l'IOException dans une RuntimeException
                if (e.getCause() instanceof MissingObjectException cause) {
                    missing = cause;
                } else {
                    throw new IllegalStateException("Dépôt " + repo.directory() + " : " + what
                            + " en échec (" + e.getCause() + ")", e);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Dépôt " + repo.directory() + " : " + what
                        + " en échec (" + e.getMessage() + ")", e);
            }
            if (attempt >= 2) {
                throw new IllegalStateException("Dépôt " + repo.directory() + " : " + what
                        + " en échec après réouverture (objet manquant " + missing.getObjectId().name()
                        + " — git gc/fetch concurrent ? clone partiel ?)", missing);
            }
            log.warn("Dépôt {} : objet manquant pendant la {} ({}) — réouverture et nouvel essai",
                    repo.directory(), what, missing.getMessage());
        }
    }

    private List<GitRepositories.LocalRepo> repositories() {
        List<GitRepositories.LocalRepo> repos;
        try {
            repos = GitRepositories.discover(root, config.repos(), config.remote());
        } catch (IOException e) {
            throw new IllegalStateException("Lecture de " + root + " impossible : " + e.getMessage(), e);
        }
        if (repos.isEmpty()) {
            throw new IllegalStateException("Aucun dépôt git sous " + root
                    + " (clones de travail ou dépôts bare, profondeur 4) — vérifier GIT_REPOS_DIR et sources.git.repos");
        }
        // Le slug identifie les chunks (slug@branche/fichier) et les MRs (git:slug:…) : deux dépôts
        // de même slug (fork dans un autre groupe) s'écraseraient mutuellement à chaque sync.
        var bySlug = new java.util.HashMap<String, GitRepositories.LocalRepo>();
        for (GitRepositories.LocalRepo repo : repos) {
            GitRepositories.LocalRepo other = bySlug.putIfAbsent(repo.slug(), repo);
            if (other != null) {
                throw new IllegalStateException("Deux dépôts résolvent le même projet « " + repo.slug() + "» : "
                        + other.directory() + " et " + repo.directory()
                        + " — retirer le doublon (fork ?) de GIT_REPOS_DIR ou restreindre sources.git.repos");
            }
        }
        Instant now = Instant.now();
        for (GitRepositories.LocalRepo repo : repos) {
            GitRepositories.fetchAge(repo, now).filter(age -> age.compareTo(STALE_FETCH) > 0).ifPresent(age ->
                    log.warn("Dépôt {} : dernier fetch il y a {} h — miroir périmé ? (scripts/git-fetch-all.sh)",
                            repo.slug(), age.toHours()));
        }
        return repos;
    }

    /** Branches configurées présentes dans le dépôt, dans l'ordre configuré. */
    private Map<String, ObjectId> branchHeads(Repository repository, GitRepositories.LocalRepo repo) throws IOException {
        var heads = new LinkedHashMap<String, ObjectId>();
        for (String branch : config.branches()) {
            GitRepositories.resolveBranch(repository, config.remote(), branch)
                    .ifPresent(head -> heads.put(branch, head));
        }
        if (heads.isEmpty()) {
            // fail-closed : un dépôt sans aucune branche cible ne peut être ni indexé ni inventorié
            // (ses MRs ouvertes seraient réconciliées comme disparues) — config à corriger
            throw new IllegalStateException("Dépôt " + repo.slug() + " : aucune des branches " + config.branches()
                    + " n'existe (ni refs/remotes/" + config.remote() + "/…, ni refs/heads/…) — aligner "
                    + "sources.git.branches, fetcher le clone, ou retirer ce dépôt de sources.git.repos");
        }
        log.info("Dépôt {} ({}, {}) : branches {}", repo.slug(), repo.fullPath(),
                repo.bare() ? "bare" : "clone", heads.keySet());
        return heads;
    }
}
