package com.domwil.xrag.adapter.out.git;

import com.domwil.xrag.domain.model.MergeRequestMeta;
import com.domwil.xrag.extraction.JiraKeys;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reconstitue les Merge Requests d'un dépôt depuis son seul historique git (aucune API).
 * Trois voies, cumulables et configurables :
 * <ol>
 *   <li><b>commits de fusion</b> (message parsé par {@link MergeCommitMessageParser}) : numéro,
 *       titre, branches, auteur et dates des commits de la branche ({@code m^1..m^2}), fichiers
 *       touchés ({@code m^1 → m}) — fidélité maximale avec la stratégie GitLab « merge commit ».
 *       Un commit de <b>squash</b> portant la référence {@code (grp/proj!153)} est aussi une MR ;</li>
 *   <li><b>séries de commits directs</b> partageant la même clé Jira (fast-forward, squash sans
 *       référence) → MR « synthétique » ; les commits sans clé Jira ne sont pas des MRs ;</li>
 *   <li><b>branches distantes non fusionnées</b> → MRs ouvertes présumées, ensemble autoritaire
 *       réconcilié à chaque sync.</li>
 * </ol>
 * Les branches cibles sont parcourues dans l'ordre configuré, chacune privée de l'historique
 * des précédentes ({@code markUninteresting}) : une fusion n'est vue qu'une fois même si
 * {@code main} contient tout {@code develop}. Le parcours est complet (pas seulement
 * first-parent) : les fusions faites derrière un {@code git pull} de synchronisation restent
 * visibles. Une fusion entre deux branches cibles ({@code develop → main}, release) est une
 * MR « d'intégration » : créée avec son numéro, mais sans listing des dizaines de commits ni
 * de leurs clés Jira (ce serait un hub — revue 2026-07, dé-hub).
 */
final class GitHistoryMergeRequestReconstructor {

    private static final Logger log = LoggerFactory.getLogger(GitHistoryMergeRequestReconstructor.class);

    /** Préfixe des ids de MRs ouvertes reconstituées depuis les branches. */
    static final String OPEN_BRANCH_ID_PREFIX = "git-branch:";
    /** Deux commits directs de même clé séparés de plus de 7 jours = deux livraisons. */
    static final Duration SERIES_GAP = Duration.ofDays(7);
    private static final String PROVENANCE_NOTE = "(historique git local, sans API)";
    /** Auteurs exclus du vote « auteur de la MR » (bots, CI). */
    // pas « noreply » : les humains de GitHub commitent en <login>@users.noreply.github.com
    private static final Pattern BOT_AUTHOR = Pattern.compile(
            "(?i)\\[bot\\]|dependabot|renovate|semantic-release|gitlab-ci|jenkins|\\bci\\b");

    record Options(boolean fromMergeCommits, boolean fromLinearCommits, boolean fromOpenBranches,
                   List<String> openBranchesExclude, Collection<String> jiraProjects,
                   int maxChangedFiles, int maxCommitsListed, String remote, String webBaseUrl) {
    }

    private final Options options;
    private final List<Pattern> excludedBranches;

    GitHistoryMergeRequestReconstructor(Options options) {
        this.options = options;
        this.excludedBranches = options.openBranchesExclude().stream().map(GitHistoryMergeRequestReconstructor::glob).toList();
    }

    /**
     * @param mergeRequests MRs reconstituées (filtrées par {@code since} pour les fusionnées)
     * @param openIds       ids de TOUTES les MRs ouvertes présumées du dépôt (jamais filtrées) —
     *                      l'instantané autoritaire de la réconciliation
     */
    record Result(List<MergeRequestMeta> mergeRequests, Set<String> openIds) {
    }

    /**
     * @param targetHeads branches cibles → tête, dans l'ordre configuré (LinkedHashMap)
     * @param since       borne basse (déjà élargie de la fenêtre de re-balayage) ; {@code null} = tout
     */
    Result reconstruct(Repository repository, GitRepositories.LocalRepo repo,
                       Map<String, ObjectId> targetHeads, Instant since) throws IOException {
        var result = new LinkedHashMap<String, MergeRequestMeta>();
        var covered = new HashSet<ObjectId>();
        var mergedSourceBranches = new HashMap<String, Instant>(); // branche source → date de fusion
        int merges = 0;
        int linear = 0;
        var previousHeads = new ArrayList<ObjectId>();
        for (var entry : targetHeads.entrySet()) {
            String branch = entry.getKey();
            List<RevCommit> commits = exclusiveHistory(repository, entry.getValue(), previousHeads);
            previousHeads.add(entry.getValue());
            if (options.fromMergeCommits()) {
                merges += fromMergeCommits(repository, repo, branch, commits, targetHeads.keySet(),
                        covered, mergedSourceBranches, since, result);
            }
            if (options.fromLinearCommits() || options.fromMergeCommits()) {
                linear += fromLinearCommits(repository, repo, branch, commits, covered, since, result);
            }
        }
        int open = 0;
        if (options.fromOpenBranches()) {
            open = fromOpenBranches(repository, repo, targetHeads, mergedSourceBranches, result);
        }
        log.info("Dépôt {} : {} MR(s) reconstituée(s) — {} fusion(s), {} série(s)/squash, {} branche(s) ouverte(s)",
                repo.slug(), result.size(), merges, linear, open);
        var openIds = new LinkedHashSet<String>();
        result.values().stream().filter(mr -> "opened".equals(mr.state())).map(MergeRequestMeta::id).forEach(openIds::add);
        return new Result(List.copyOf(result.values()), openIds);
    }

    // ---- voie 1 : commits de fusion ------------------------------------------------

    private int fromMergeCommits(Repository repository, GitRepositories.LocalRepo repo, String branch,
                                 List<RevCommit> commits, Set<String> targetBranches, Set<ObjectId> covered,
                                 Map<String, Instant> mergedSourceBranches, Instant since,
                                 Map<String, MergeRequestMeta> result) throws IOException {
        int count = 0;
        for (RevCommit merge : commits) {
            if (merge.getParentCount() < 2) {
                continue;
            }
            Optional<MergeCommitMessageParser.ParsedMerge> parsed =
                    MergeCommitMessageParser.parse(merge.getFullMessage());
            if (parsed.isEmpty()) {
                continue; // fusion de synchronisation ou format inconnu : pas une MR
            }
            var p = parsed.get();
            boolean sourceIsTarget = p.sourceBranch() != null && targetBranches.contains(p.sourceBranch());
            if (sourceIsTarget && p.iid() <= 0) {
                // « Merge branch 'main' into feature/x » (synchronisation d'une cible dans une
                // feature, vue depuis main une fois la feature fusionnée) : pas une MR, et ses
                // « commits de branche » sont ceux de main — ne surtout pas les couvrir
                continue;
            }
            Instant mergedAt = committerInstant(merge);
            if (p.sourceBranch() != null) {
                mergedSourceBranches.merge(p.sourceBranch(), mergedAt, (a, b) -> a.isAfter(b) ? a : b);
            }
            boolean integration = sourceIsTarget
                    && (p.targetBranch() == null || targetBranches.contains(p.targetBranch()));
            List<RevCommit> branchCommits = List.of();
            if (!integration) {
                // Les commits de la branche fusionnée sont « couverts » AVANT le filtre temporel :
                // la voie 2 ne doit jamais les re-fabriquer en MR synthétique.
                branchCommits = range(repository, merge.getParent(1), merge.getParent(0));
                branchCommits.forEach(c -> covered.add(c.getId()));
            }
            if (since != null && mergedAt.isBefore(since)) {
                continue;
            }
            String id = p.iid() > 0 ? "git:" + repo.slug() + ":" + p.iid()
                    : "git:" + repo.slug() + ":merge:" + merge.name();
            if (result.containsKey(id)) {
                continue;
            }
            String sourceBranch = p.sourceBranch();
            String title = firstNonBlank(p.title(),
                    sourceBranch != null ? MergeCommitMessageParser.humanizeBranch(sourceBranch) : null,
                    "(sans titre) " + abbrev(merge));
            var description = new StringBuilder();
            if (p.description() != null && !p.description().isBlank()) {
                description.append(p.description()).append("\n\n");
            }
            Set<String> keys;
            if (integration) {
                keys = jiraKeys(merge.getFullMessage());
                description.append("Fusion d'intégration ").append(sourceBranch).append(" → ")
                        .append(p.targetBranch() != null ? p.targetBranch() : branch)
                        .append(" (commits non détaillés : ils appartiennent à leurs propres MRs).\n");
            } else {
                keys = jiraKeys(merge.getFullMessage(), sourceBranch, branchCommits);
                description.append(commitList(branchCommits, branch));
            }
            appendJiraKeys(description, keys);
            description.append("Reconstituée depuis le commit de fusion ").append(abbrev(merge))
                    .append(' ').append(PROVENANCE_NOTE);
            result.put(id, new MergeRequestMeta(
                    id, repo.slug(), Math.max(p.iid(), 0), title, description.toString(), "merged",
                    majorityAuthor(branchCommits).orElse(identity(merge.getAuthorIdent())),
                    sourceBranch,
                    p.targetBranch() != null ? p.targetBranch() : branch,
                    mergeUrl(repo, p, merge),
                    List.of(),
                    integration ? List.of() : changedFiles(repository, merge.getParent(0), merge),
                    earliestAuthorInstant(branchCommits).orElse(mergedAt),
                    mergedAt, mergedAt));
            count++;
        }
        return count;
    }

    // ---- voie 2 : squash référencés et séries de commits directs par clé Jira --------

    private int fromLinearCommits(Repository repository, GitRepositories.LocalRepo repo, String branch,
                                  List<RevCommit> commits, Set<ObjectId> covered, Instant since,
                                  Map<String, MergeRequestMeta> result) throws IOException {
        int count = 0;
        var run = new ArrayList<RevCommit>(); // du plus récent au plus ancien
        String runKey = null;
        for (RevCommit commit : commits) {
            if (commit.getParentCount() >= 2 || covered.contains(commit.getId())) {
                continue; // fusions et commits déjà portés par une MR : ne cassent pas la série
            }
            // Squash GitLab avec « %{title} (%{reference}) » : 1 commit = 1 MR numérotée —
            // seules les formes écrites par GitLab comptent (un commit qui CITE !153 n'est pas !153)
            Optional<MergeCommitMessageParser.MergeReference> reference = options.fromMergeCommits()
                    ? MergeCommitMessageParser.squashReference(commit.getFullMessage()) : Optional.empty();
            if (reference.isPresent()) {
                count += flushRun(repository, repo, branch, run, runKey, since, result);
                run.clear();
                runKey = null;
                count += squashMergeRequest(repository, repo, branch, commit, reference.get(), since, result);
                continue;
            }
            if (!options.fromLinearCommits()) {
                continue;
            }
            String key = firstJiraKey(commit.getFullMessage());
            boolean gap = !run.isEmpty()
                    && Duration.between(committerInstant(commit), committerInstant(run.getLast())).compareTo(SERIES_GAP) > 0;
            if (key == null || !key.equals(runKey) || gap) {
                count += flushRun(repository, repo, branch, run, runKey, since, result);
                run.clear();
                runKey = key;
            }
            if (key != null) {
                run.add(commit);
            }
        }
        count += flushRun(repository, repo, branch, run, runKey, since, result);
        return count;
    }

    private int squashMergeRequest(Repository repository, GitRepositories.LocalRepo repo, String branch,
                                   RevCommit commit, MergeCommitMessageParser.MergeReference reference,
                                   Instant since, Map<String, MergeRequestMeta> result) throws IOException {
        Instant mergedAt = committerInstant(commit);
        if (since != null && mergedAt.isBefore(since)) {
            return 0;
        }
        String id = "git:" + repo.slug() + ":" + reference.iid();
        if (result.containsKey(id)) {
            return 0;
        }
        var description = new StringBuilder();
        String body = bodyOf(commit);
        if (!body.isBlank()) {
            description.append(body).append("\n\n");
        }
        Set<String> keys = jiraKeys(commit.getFullMessage());
        appendJiraKeys(description, keys);
        description.append("Commit de squash ").append(abbrev(commit)).append(" sur ").append(branch)
                .append(" portant la référence de la MR ").append(PROVENANCE_NOTE);
        var parsed = new MergeCommitMessageParser.ParsedMerge("gitlab", reference.iid(), reference.reference(),
                null, branch, null, null);
        result.put(id, new MergeRequestMeta(
                id, repo.slug(), reference.iid(),
                MergeCommitMessageParser.stripReferenceSuffix(commit.getShortMessage()),
                description.toString(), "merged",
                identity(commit.getAuthorIdent()), null, branch,
                mergeUrl(repo, parsed, commit), List.of(),
                changedFiles(repository, commit.getParentCount() > 0 ? commit.getParent(0) : null, commit),
                commit.getAuthorIdent().getWhenAsInstant(), mergedAt, mergedAt));
        return 1;
    }

    private int flushRun(Repository repository, GitRepositories.LocalRepo repo, String branch,
                         List<RevCommit> run, String key, Instant since,
                         Map<String, MergeRequestMeta> result) throws IOException {
        if (run.isEmpty() || key == null) {
            return 0;
        }
        RevCommit newest = run.getFirst();
        RevCommit oldest = run.getLast();
        Instant updatedAt = committerInstant(newest);
        if (since != null && updatedAt.isBefore(since)) {
            return 0;
        }
        // id ancré sur le PREMIER commit : une série qui s'allonge garde son identité (pas de doublon)
        String id = "git:" + repo.slug() + ":commits:" + oldest.name();
        if (result.containsKey(id)) {
            return 0;
        }
        var description = new StringBuilder();
        if (run.size() == 1) {
            String body = bodyOf(newest);
            if (!body.isBlank()) {
                description.append(body).append("\n\n");
            }
        }
        description.append(commitList(run, branch));
        appendJiraKeys(description, jiraKeys(null, null, run));
        description.append("Série de commits directs sur ").append(branch)
                .append(" sans commit de fusion ").append(PROVENANCE_NOTE);
        var files = new LinkedHashSet<String>();
        for (RevCommit commit : run) {
            if (files.size() >= options.maxChangedFiles()) {
                break;
            }
            files.addAll(changedFiles(repository, commit.getParentCount() > 0 ? commit.getParent(0) : null, commit));
        }
        String title = oldest.getShortMessage().isBlank() ? "(sans titre) " + abbrev(oldest) : oldest.getShortMessage();
        result.put(id, new MergeRequestMeta(
                id, repo.slug(), 0, title, description.toString(), "merged",
                majorityAuthor(run).orElse(identity(newest.getAuthorIdent())),
                null, branch,
                commitUrl(repo, newest),
                List.of(),
                List.copyOf(files).subList(0, Math.min(files.size(), options.maxChangedFiles())),
                earliestAuthorInstant(run).orElse(updatedAt),
                updatedAt, updatedAt));
        return 1;
    }

    // ---- voie 3 : branches distantes non fusionnées -----------------------------------

    private record OpenBranch(String name, RevCommit tip, RevCommit base, String target, List<RevCommit> commits) {
    }

    private int fromOpenBranches(Repository repository, GitRepositories.LocalRepo repo,
                                 Map<String, ObjectId> targetHeads, Map<String, Instant> mergedSourceBranches,
                                 Map<String, MergeRequestMeta> result) throws IOException {
        int count = 0;
        try (RevWalk walk = new RevWalk(repository)) {
            var targets = parseTargets(walk, targetHeads);
            for (Ref ref : GitRepositories.branchRefs(repository, options.remote())) {
                Optional<OpenBranch> candidate = openBranch(repository, walk, ref, targets, targetHeads.keySet());
                if (candidate.isEmpty()) {
                    continue;
                }
                OpenBranch b = candidate.get();
                // Branche déjà fusionnée par squash/rebase (tip non ancêtre de la cible) mais dont
                // le nom apparaît dans un commit de fusion postérieur : pas une MR ouverte.
                Instant mergedAt = mergedSourceBranches.get(b.name());
                if (mergedAt != null && !mergedAt.isBefore(committerInstant(b.tip()))) {
                    continue;
                }
                String id = openBranchId(repo, b.name());
                RevCommit newest = b.commits().getFirst();
                String title = b.commits().size() == 1 && !newest.getShortMessage().isBlank()
                        ? newest.getShortMessage() : MergeCommitMessageParser.humanizeBranch(b.name());
                var description = new StringBuilder(commitList(b.commits(), b.name()));
                appendJiraKeys(description, jiraKeys(null, b.name(), b.commits()));
                description.append("Branche « ").append(b.name()).append(" » non fusionnée dans ").append(b.target())
                        .append(" : MR ouverte présumée ").append(PROVENANCE_NOTE);
                Instant updatedAt = committerInstant(newest);
                result.put(id, new MergeRequestMeta(
                        id, repo.slug(), 0, title, description.toString(), "opened",
                        majorityAuthor(b.commits()).orElse(identity(newest.getAuthorIdent())),
                        b.name(), b.target(),
                        treeUrl(repo, b.name()),
                        List.of(),
                        changedFiles(repository, b.base(), b.tip()),
                        earliestAuthorInstant(b.commits()).orElse(updatedAt),
                        updatedAt, null));
                count++;
            }
        }
        return count;
    }

    private Optional<OpenBranch> openBranch(Repository repository, RevWalk walk, Ref ref,
                                            Map<String, RevCommit> targets, Set<String> targetNames) throws IOException {
        if (ref.isSymbolic() || ref.getObjectId() == null) {
            return Optional.empty(); // refs/remotes/origin/HEAD
        }
        String name = GitRepositories.shortBranchName(ref, options.remote());
        if (targetNames.contains(name) || "HEAD".equals(name) || excluded(name)) {
            return Optional.empty();
        }
        RevCommit tip;
        try {
            tip = walk.parseCommit(ref.getObjectId());
        } catch (IOException e) {
            return Optional.empty(); // ref vers un tag annoté ou objet absent
        }
        for (RevCommit target : targets.values()) {
            if (walk.isMergedInto(tip, target)) {
                return Optional.empty();
            }
        }
        // cible la plus proche : celle dont la branche diverge le moins
        String closestTarget = null;
        RevCommit closestBase = null;
        List<RevCommit> closestCommits = null;
        for (var target : targets.entrySet()) {
            Optional<RevCommit> base = mergeBase(repository, tip, target.getValue());
            if (base.isEmpty()) {
                continue;
            }
            List<RevCommit> ahead = range(repository, tip, base.get());
            if (closestCommits == null || ahead.size() < closestCommits.size()) {
                closestTarget = target.getKey();
                closestBase = base.get();
                closestCommits = ahead;
            }
        }
        if (closestCommits == null || closestCommits.isEmpty()) {
            return Optional.empty(); // historique sans ancêtre commun, ou branche vide
        }
        return Optional.of(new OpenBranch(name, tip, closestBase, closestTarget, closestCommits));
    }

    private static String openBranchId(GitRepositories.LocalRepo repo, String branch) {
        return OPEN_BRANCH_ID_PREFIX + repo.slug() + ":" + branch;
    }

    private boolean excluded(String branch) {
        return excludedBranches.stream().anyMatch(p -> p.matcher(branch).matches());
    }

    /** Glob minimal ({@code release/*}, {@code revert-*}) → regex ; {@code *} traverse aussi les {@code /}. */
    static Pattern glob(String glob) {
        var regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    // ---- primitives git ------------------------------------------------------------------

    private static Map<String, RevCommit> parseTargets(RevWalk walk, Map<String, ObjectId> targetHeads) throws IOException {
        var targets = new LinkedHashMap<String, RevCommit>();
        for (var entry : targetHeads.entrySet()) {
            targets.put(entry.getKey(), walk.parseCommit(entry.getValue()));
        }
        return targets;
    }

    /** Commits atteignables depuis {@code head} mais depuis aucune des têtes déjà parcourues. */
    private static List<RevCommit> exclusiveHistory(Repository repository, ObjectId head,
                                                    List<ObjectId> uninteresting) throws IOException {
        var commits = new ArrayList<RevCommit>();
        try (RevWalk walk = new RevWalk(repository)) {
            // TOPO_KEEP_BRANCH_TOGETHER (et non TOPO, qui ne garantit que « enfants avant
            // parents ») : les commits d'une même lignée restent contigus même quand un « git
            // pull » a entrelacé les dates de deux auteurs — sinon leurs séries par clé Jira
            // seraient fragmentées en autant de MRs synthétiques (vérifié sur JGit 7.8)
            walk.sort(RevSort.TOPO_KEEP_BRANCH_TOGETHER, true);
            walk.sort(RevSort.COMMIT_TIME_DESC, true);
            walk.markStart(walk.parseCommit(head));
            for (ObjectId other : uninteresting) {
                walk.markUninteresting(walk.parseCommit(other));
            }
            for (RevCommit commit : walk) {
                commits.add(commit);
            }
        }
        return commits;
    }

    /** Commits de {@code exclude..include} (les plus récents d'abord). */
    private static List<RevCommit> range(Repository repository, ObjectId include, ObjectId exclude)
            throws IOException {
        var commits = new ArrayList<RevCommit>();
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(include));
            walk.markUninteresting(walk.parseCommit(exclude));
            for (RevCommit commit : walk) {
                commits.add(commit);
            }
        }
        return commits;
    }

    private static Optional<RevCommit> mergeBase(Repository repository, RevCommit a, RevCommit b)
            throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(a.getId()));
            walk.markStart(walk.parseCommit(b.getId()));
            return Optional.ofNullable(walk.next());
        }
    }

    /** Fichiers touchés entre {@code from} (null = arbre vide) et {@code to}, plafonnés. */
    private List<String> changedFiles(Repository repository, RevCommit from, RevCommit to) throws IOException {
        var files = new ArrayList<String>();
        try (DiffFormatter diff = new DiffFormatter(DisabledOutputStream.INSTANCE);
             RevWalk walk = new RevWalk(repository)) {
            diff.setRepository(repository);
            // pas de détection de renommage : elle lit le contenu des blobs (coût, clones partiels) ;
            // un renommage donne DELETE + ADD, ce qui suffit aux arêtes MODIFIES
            diff.setDetectRenames(false);
            RevCommit parsedTo = walk.parseCommit(to.getId());
            List<DiffEntry> entries = from == null
                    ? diff.scan(null, parsedTo.getTree())
                    : diff.scan(walk.parseCommit(from.getId()).getTree(), parsedTo.getTree());
            for (DiffEntry entry : entries) {
                if (files.size() >= options.maxChangedFiles()) {
                    break;
                }
                files.add(entry.getChangeType() == DiffEntry.ChangeType.DELETE
                        ? entry.getOldPath() : entry.getNewPath());
            }
        }
        return files;
    }

    // ---- mise en forme ------------------------------------------------------------------

    private String commitList(List<RevCommit> newestFirst, String branch) {
        var sb = new StringBuilder("Commits (").append(newestFirst.size()).append(") sur ").append(branch).append(" :\n");
        int listed = Math.min(newestFirst.size(), options.maxCommitsListed());
        for (int i = newestFirst.size() - 1, n = 0; i >= 0 && n < listed; i--, n++) {
            RevCommit commit = newestFirst.get(i);
            sb.append("- ").append(abbrev(commit)).append(' ').append(commit.getShortMessage()).append('\n');
        }
        if (listed < newestFirst.size()) {
            sb.append("- … ").append(newestFirst.size() - listed).append(" autre(s) commit(s)\n");
        }
        return sb.toString();
    }

    /** Clés Jira (normalisées en MAJUSCULES) des messages, de la branche et des commits. */
    private Set<String> jiraKeys(String message, String branch, List<RevCommit> commits) {
        var keys = new LinkedHashSet<String>();
        if (message != null) {
            keys.addAll(jiraKeys(message));
        }
        if (branch != null) {
            keys.addAll(jiraKeys(branch));
        }
        for (RevCommit commit : commits) {
            keys.addAll(jiraKeys(commit.getFullMessage()));
        }
        return keys;
    }

    private Set<String> jiraKeys(String text) {
        return JiraKeys.in(text, options.jiraProjects());
    }

    private String firstJiraKey(String message) {
        Set<String> keys = jiraKeys(message);
        return keys.isEmpty() ? null : keys.iterator().next();
    }

    /** Ligne « Clés Jira : … » : le mapper MR (regex majuscule) retrouve ainsi même les clés en minuscules. */
    private static void appendJiraKeys(StringBuilder description, Set<String> keys) {
        if (!keys.isEmpty()) {
            description.append("Clés Jira : ").append(String.join(", ", keys)).append('\n');
        }
    }

    private String mergeUrl(GitRepositories.LocalRepo repo, MergeCommitMessageParser.ParsedMerge parsed,
                            RevCommit merge) {
        String origin = origin(repo);
        if (origin == null) {
            return null;
        }
        if (parsed.iid() <= 0) {
            return origin + "/" + repo.fullPath() + "/-/commit/" + merge.name();
        }
        String projectUrl = origin + "/" + (parsed.reference() != null ? parsed.reference() : repo.fullPath());
        return switch (parsed.hosting()) {
            case "github" -> projectUrl + "/pull/" + parsed.iid();
            case "bitbucket" -> projectUrl + "/pull-requests/" + parsed.iid();
            case "azure" -> projectUrl + "/pullrequest/" + parsed.iid();
            default -> projectUrl + "/-/merge_requests/" + parsed.iid();
        };
    }

    private String commitUrl(GitRepositories.LocalRepo repo, RevCommit commit) {
        String origin = origin(repo);
        return origin == null ? null : origin + "/" + repo.fullPath() + "/-/commit/" + commit.name();
    }

    private String treeUrl(GitRepositories.LocalRepo repo, String branch) {
        String origin = origin(repo);
        return origin == null ? null : origin + "/" + repo.fullPath() + "/-/tree/" + branch;
    }

    private String origin(GitRepositories.LocalRepo repo) {
        return options.webBaseUrl() != null ? options.webBaseUrl() : repo.webOrigin();
    }

    /** Identité stable d'un auteur : partie locale de l'email en minuscules (≈ username GitLab), sinon le nom. */
    static String identity(PersonIdent ident) {
        String email = ident.getEmailAddress();
        if (email != null && email.contains("@") && email.indexOf('@') > 0) {
            return email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
        }
        String name = ident.getName();
        return name == null || name.isBlank() ? "?" : name.strip();
    }

    private static Optional<String> majorityAuthor(List<RevCommit> commits) {
        var votes = new HashMap<String, Integer>();
        String best = null;
        int bestCount = 0;
        for (RevCommit commit : commits) {
            PersonIdent author = commit.getAuthorIdent();
            if (BOT_AUTHOR.matcher(author.getName() + " " + author.getEmailAddress()).find()) {
                continue;
            }
            String name = identity(author);
            int count = votes.merge(name, 1, Integer::sum);
            if (count > bestCount) {
                best = name;
                bestCount = count;
            }
        }
        if (best == null && !commits.isEmpty()) {
            best = identity(commits.getLast().getAuthorIdent()); // que des bots : le premier commit
        }
        return Optional.ofNullable(best);
    }

    private static Optional<Instant> earliestAuthorInstant(List<RevCommit> commits) {
        Instant earliest = null;
        for (RevCommit commit : commits) {
            Instant when = commit.getAuthorIdent().getWhenAsInstant();
            if (earliest == null || when.isBefore(earliest)) {
                earliest = when;
            }
        }
        return Optional.ofNullable(earliest);
    }

    private static Instant committerInstant(RevCommit commit) {
        return commit.getCommitterIdent().getWhenAsInstant();
    }

    /** Corps du message sans sa première ligne (le sujet), fins de ligne normalisées. */
    private static String bodyOf(RevCommit commit) {
        String full = commit.getFullMessage().replace("\r\n", "\n").replace('\r', '\n').strip();
        int newline = full.indexOf('\n');
        return newline < 0 ? "" : full.substring(newline + 1).strip();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private static String abbrev(RevCommit commit) {
        return commit.name().substring(0, 7);
    }
}
