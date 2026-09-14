package com.domwil.xrag.adapter.out.git;

import com.domwil.xrag.config.TeamConfig;
import com.domwil.xrag.domain.model.MergeRequestMeta;
import com.domwil.xrag.domain.model.SourceDocument;
import com.domwil.xrag.domain.port.MergeRequestConnector;
import com.domwil.xrag.domain.port.SourceConnector;
import org.eclipse.jgit.errors.MissingObjectException;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            withRetry(repo, "lecture du code", repository -> {
                for (var head : branchHeads(repository, repo).entrySet()) {
                    int emitted = codeReader.stream(repository, repo, head.getKey(), head.getValue(), sink);
                    log.info("Dépôt {} @{} : {} fichier(s) indexable(s)", repo.slug(), head.getKey(), emitted);
                }
            });
        }
    }

    @Override
    public List<MergeRequestMeta> fetchUpdatedAfter(Instant since) {
        Instant effectiveSince = since == null ? null
                : since.minus(config.mergeRequests().rescanDays(), ChronoUnit.DAYS);
        var mergeRequests = new ArrayList<MergeRequestMeta>();
        for (GitRepositories.LocalRepo repo : repositories()) {
            withRetry(repo, "reconstitution des MRs", repository -> {
                Map<String, ObjectId> heads = branchHeads(repository, repo);
                if (!heads.isEmpty()) {
                    mergeRequests.addAll(reconstructor.reconstruct(repository, repo, heads, effectiveSince));
                }
            });
        }
        return mergeRequests;
    }

    /**
     * Instantané autoritaire des MRs ouvertes (branches non fusionnées) recalculé à la demande ;
     * vide si un dépôt est illisible — jamais de réconciliation sur un instantané partiel.
     */
    @Override
    public Optional<Set<String>> currentlyOpenIds() {
        var ids = new LinkedHashSet<String>();
        try {
            for (GitRepositories.LocalRepo repo : repositories()) {
                withRetry(repo, "inventaire des branches ouvertes", repository -> {
                    Map<String, ObjectId> heads = branchHeads(repository, repo);
                    if (!heads.isEmpty()) {
                        ids.addAll(reconstructor.openBranchIds(repository, repo, heads));
                    }
                });
            }
        } catch (RuntimeException e) {
            log.warn("Inventaire des branches ouvertes incomplet ({}) : réconciliation des MRs ouvertes sautée",
                    e.getMessage());
            return Optional.empty();
        }
        return Optional.of(ids);
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
            try (Repository repository = GitRepositories.open(repo)) {
                action.run(repository);
                return;
            } catch (MissingObjectException e) {
                if (attempt >= 2) {
                    throw new IllegalStateException("Dépôt " + repo.directory() + " : " + what
                            + " en échec après réouverture (objet manquant " + e.getObjectId().name()
                            + " — git gc/fetch concurrent ? clone partiel ?)", e);
                }
                log.warn("Dépôt {} : objet manquant pendant la {} ({}) — réouverture et nouvel essai",
                        repo.directory(), what, e.getMessage());
            } catch (IOException e) {
                throw new IllegalStateException("Dépôt " + repo.directory() + " : " + what
                        + " en échec (" + e.getMessage() + ")", e);
            }
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
            log.warn("Dépôt {} : aucune des branches {} n'existe (ni refs/remotes/{}/…, ni refs/heads/…) — "
                    + "un « git fetch » est-il fait sur ce clone ?", repo.slug(), config.branches(), config.remote());
        } else {
            log.info("Dépôt {} ({}, {}) : branches {}", repo.slug(), repo.fullPath(),
                    repo.bare() ? "bare" : "clone", heads.keySet());
        }
        return heads;
    }
}
