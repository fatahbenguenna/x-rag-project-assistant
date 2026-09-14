package com.domwil.xrag.adapter.out.git;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Découverte et ouverture (lecture seule) des dépôts git locaux sous le dossier racine
 * configuré : clones de travail ({@code .git} dossier ou fichier) et dépôts bare/mirror
 * ({@code projet.git}, le standard recommandé). Profondeur 4 pour respecter la disposition
 * {@code root/groupe/sous-groupe/projet} d'un groupe GitLab. Le <b>slug</b> (nom technique du projet,
 * résolu ensuite par les alias) vient de l'URL du remote quand elle existe — le nom du
 * dossier local n'est pas fiable ({@code fps-pos-v2}, {@code FPS_POS}) — sinon du dossier.
 */
final class GitRepositories {

    private static final Logger log = LoggerFactory.getLogger(GitRepositories.class);
    /** {@code root/grp/sous-grp/sous-sous-grp/projet.git} : les sous-groupes GitLab s'imbriquent. */
    private static final int MAX_DEPTH = 4;
    /** Racines qui exposeraient bien plus que les dépôts de l'équipe (secrets, dépôts perso). */
    private static final Set<String> FORBIDDEN_ROOTS = Set.of("/", "/home", "/root", "/mnt", "/mnt/c", "/tmp",
            "/etc", "/var", "/usr", "/opt", "/app", "/config");
    /** {@code git@host:grp/proj.git}, {@code https://host/grp/proj.git}, {@code ssh://git@host:2222/grp/proj} */
    private static final Pattern REMOTE_URL = Pattern.compile(
            "^(?:(?<scheme>[a-z+]+)://(?:[^@/]+@)?(?<host>[^:/]+)(?::(?<port>\\d+))?/|(?:[^@]+@)?(?<sshHost>[^:/]+):)(?<path>.+?)(?:\\.git)?/?$");

    /**
     * @param slug         nom technique du projet (dernier segment du chemin distant, sans
     *                     {@code .git}) — devient le {@code project} des documents et des MRs
     * @param fullPath     chemin complet du projet sur la forge ({@code grp/sub/proj}) si le remote
     *                     le donne, sinon chemin relatif à la racine — sert aux URLs (jamais null)
     * @param webOrigin    origine web déduite du remote ({@code https://hote}) ou null
     * @param directory    dossier du clone (worktree) ou du dépôt bare
     * @param lastFetchAt  date du dernier fetch (mtime de FETCH_HEAD) ou null
     */
    record LocalRepo(String slug, String fullPath, String webOrigin, Path directory, Path gitDir, boolean bare,
                     boolean shallow, Instant lastFetchAt) {
    }

    private GitRepositories() {
    }

    /** Dépôts explicitement listés (relatifs à la racine, jamais au-dessus) ou découverts. */
    static List<LocalRepo> discover(Path root, List<String> explicit, String remote) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("sources.git.root introuvable dans le conteneur : " + root
                    + " — vérifier le montage GIT_REPOS_DIR (docker-compose) et le chemin");
        }
        Path realRoot = root.toRealPath();
        if (FORBIDDEN_ROOTS.contains(realRoot.toString())) {
            throw new IllegalStateException("sources.git.root = " + realRoot + " exposerait tout le système de "
                    + "fichiers au conteneur (et au chat) : monter un dossier DÉDIÉ aux dépôts de l'équipe");
        }
        var repos = new ArrayList<LocalRepo>();
        if (!explicit.isEmpty()) {
            // Périmètre explicite = contrat : une entrée absente ou illisible est une erreur de
            // configuration (fail-closed), jamais un dépôt silencieusement ignoré dont les MRs
            // ouvertes seraient ensuite réconciliées comme disparues.
            for (String sub : explicit) {
                Path candidate = realRoot.resolve(sub).normalize();
                if (!Files.isDirectory(candidate) || !candidate.toRealPath().startsWith(realRoot)) {
                    throw new IllegalStateException("sources.git.repos : « " + sub + " » absent ou hors de la racine "
                            + realRoot + " — corriger la liste ou monter le dépôt");
                }
                repos.add(asRepository(realRoot, candidate.toRealPath(), remote).orElseThrow(() ->
                        new IllegalStateException("sources.git.repos : « " + sub
                                + " » n'est pas un dépôt git (ni clone de travail, ni bare)")));
            }
            return repos;
        }
        scan(realRoot, realRoot, 1, remote, repos);
        repos.sort((a, b) -> a.fullPath().compareTo(b.fullPath()));
        return repos;
    }

    private static void scan(Path root, Path dir, int depth, String remote, List<LocalRepo> found) throws IOException {
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.sorted().toList()) {
                // symlinks ignorés : un lien hôte ne résout pas dans le conteneur, et un lien vers
                // l'extérieur de la racine élargirait le périmètre indexé
                if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                        || child.getFileName().toString().startsWith(".")) {
                    continue;
                }
                Optional<LocalRepo> repo = asRepository(root, child, remote);
                if (repo.isPresent()) {
                    found.add(repo.get());
                } else if (depth < MAX_DEPTH) {
                    scan(root, child, depth + 1, remote, found);
                }
            }
        }
    }

    static Optional<LocalRepo> asRepository(Path root, Path dir, String remote) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        Path dotGit = dir.resolve(".git");
        boolean workTree = Files.isDirectory(dotGit);
        Path gitDir = workTree ? dotGit : dir;
        boolean bare = false;
        if (Files.isRegularFile(dotGit)) {
            // worktree lié / sous-module : « gitdir: <chemin> » — un chemin absolu de l'hôte
            // n'existe pas dans le conteneur : on prévient plutôt que d'échouer en silence
            Optional<Path> linked = linkedGitDir(dir, dotGit);
            if (linked.isEmpty()) {
                log.warn("{} : fichier .git (worktree lié ou sous-module) dont la cible gitdir n'est pas visible "
                        + "dans le conteneur — monter le dépôt principal ou utiliser un miroir bare", dir);
                return Optional.empty();
            }
            workTree = true;
            gitDir = linked.get();
        }
        if (!workTree) {
            bare = RepositoryCache.FileKey.isGitRepository(dir.toFile(), FS.DETECTED);
        }
        if (!workTree && !bare) {
            return Optional.empty();
        }
        String dirName = dir.getFileName().toString();
        if (bare && dirName.endsWith(".git")) {
            dirName = dirName.substring(0, dirName.length() - 4);
        }
        String relative = root.equals(dir) ? dirName : root.relativize(dir).toString().replace('\\', '/');
        if (bare && relative.endsWith(".git")) {
            relative = relative.substring(0, relative.length() - 4);
        }
        String slug = dirName;
        String fullPath = relative;
        String webOrigin = null;
        try (Repository repository = open(new LocalRepo(dirName, relative, null, dir, gitDir, bare, false, null))) {
            Optional<RemoteCoordinates> coordinates = remoteCoordinates(repository.getConfig(), remote);
            if (coordinates.isPresent()) {
                slug = coordinates.get().slug();
                fullPath = coordinates.get().path() != null ? coordinates.get().path() : relative;
                webOrigin = coordinates.get().webOrigin();
            } else {
                log.warn("{} : pas d'URL de remote « {} » lisible — slug déduit du nom de dossier « {} »",
                        dir, remote, slug);
            }
            if (repository.getConfig().getBoolean("extensions", null, "partialclone", false)
                    || repository.getConfig().getString("remote", remote, "promisor") != null) {
                throw new IllegalStateException(dir + " : clone PARTIEL (--filter) non supporté — JGit ne peut "
                        + "pas récupérer les objets manquants ; recloner en miroir complet");
            }
            boolean shallow = Files.exists(repository.getDirectory().toPath().resolve("shallow"));
            if (shallow) {
                log.warn("{} : clone SHALLOW (--depth) — historique tronqué, MRs anciennes irrécupérables : "
                        + "« git fetch --unshallow » recommandé", dir);
            }
            Path fetchHead = repository.getDirectory().toPath().resolve("FETCH_HEAD");
            Instant lastFetch = Files.exists(fetchHead) ? Files.getLastModifiedTime(fetchHead).toInstant() : null;
            return Optional.of(new LocalRepo(slug, fullPath, webOrigin, dir, gitDir, bare, shallow, lastFetch));
        } catch (IOException e) {
            // fail-closed : un dépôt présent mais illisible fait échouer la sync (statut visible)
            throw new IllegalStateException(dir + " : dépôt illisible (" + e.getMessage() + ")", e);
        }
    }

    /** Cible d'un fichier {@code .git} ({@code gitdir: …}), si elle existe dans le conteneur. */
    private static Optional<Path> linkedGitDir(Path dir, Path dotGitFile) {
        try {
            String first = Files.readAllLines(dotGitFile).stream().findFirst().orElse("");
            if (!first.startsWith("gitdir:")) {
                return Optional.empty();
            }
            Path target = dir.resolve(first.substring("gitdir:".length()).strip()).normalize();
            return Files.isDirectory(target) ? Optional.of(target) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    record RemoteCoordinates(String slug, String path, String webOrigin) {
    }

    /** Coordonnées du projet déduites de {@code remote.<remote>.url} (jamais loggée : elle peut porter un token). */
    static Optional<RemoteCoordinates> remoteCoordinates(Config config, String remote) {
        String url = config.getString("remote", remote, "url");
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return parseRemoteUrl(url.strip());
    }

    static Optional<RemoteCoordinates> parseRemoteUrl(String url) {
        // query / fragment (token ?private_token=…) : jamais dans un chemin, un log ou une URL
        int cut = indexOfAny(url, '?', '#');
        if (cut >= 0) {
            url = url.substring(0, cut);
        }
        // remote local (file://, chemin absolu, lettre de lecteur) : seul le slug a un sens
        if (url.startsWith("file://") || url.startsWith("/") || url.startsWith(".") || url.matches("^[A-Za-z]:[\\\\/].*")) {
            String trimmed = url.replaceAll("[/\\\\]+$", "");
            String last = trimmed.substring(Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\')) + 1);
            if (last.endsWith(".git")) {
                last = last.substring(0, last.length() - 4);
            }
            return last.isBlank() ? Optional.empty() : Optional.of(new RemoteCoordinates(last, null, null));
        }
        Matcher m = REMOTE_URL.matcher(url);
        if (!m.matches()) {
            return Optional.empty();
        }
        String path = m.group("path");
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isBlank() || path.contains("..")) {
            return Optional.empty();
        }
        String slug = path.substring(path.lastIndexOf('/') + 1);
        String host = m.group("host") != null ? m.group("host") : m.group("sshHost");
        String scheme = m.group("scheme");
        String port = m.group("port");
        // un port n'a de sens pour le web qu'en http(s) : le 2222 d'un ssh:// n'est pas celui de l'UI
        boolean web = "http".equals(scheme) || "https".equals(scheme);
        String webOrigin = host == null || "file".equals(scheme) ? null
                : ("http".equals(scheme) ? "http://" : "https://") + host + (web && port != null ? ":" + port : "");
        return Optional.of(new RemoteCoordinates(slug, path, webOrigin));
    }

    private static int indexOfAny(String text, char a, char b) {
        int i = text.indexOf(a);
        int j = text.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    /** Ouverture lecture seule ; à fermer par l'appelant (try-with-resources). */
    static Repository open(LocalRepo repo) throws IOException {
        // gitDir explicite (résolu à la découverte) : jamais de remontée vers un dossier parent si
        // le clone a disparu entre-temps (findGitDir chercherait plus haut)
        var builder = new FileRepositoryBuilder().setMustExist(true).setGitDir(repo.gitDir().toFile());
        if (repo.bare()) {
            builder.setBare();
        } else {
            builder.setWorkTree(repo.directory().toFile());
        }
        return builder.build();
    }

    /**
     * Tête d'une branche : ref de suivi du remote d'abord (mise à jour par {@code git fetch},
     * sans checkout — c'est elle qui est fraîche), sinon la branche locale (dépôt bare/mirror).
     */
    static Optional<ObjectId> resolveBranch(Repository repository, String remote, String branch) throws IOException {
        for (String name : List.of("refs/remotes/" + remote + "/" + branch, "refs/heads/" + branch)) {
            Ref ref = repository.exactRef(name);
            if (ref != null && ref.getObjectId() != null) {
                return Optional.of(ref.getObjectId());
            }
        }
        return Optional.empty();
    }

    /** Refs des branches candidates aux MRs ouvertes : suivi du remote, sinon locales (bare). */
    static List<Ref> branchRefs(Repository repository, String remote) throws IOException {
        String remotePrefix = "refs/remotes/" + remote + "/";
        List<Ref> remoteRefs = repository.getRefDatabase().getRefsByPrefix(remotePrefix);
        if (!remoteRefs.isEmpty()) {
            return remoteRefs;
        }
        return repository.getRefDatabase().getRefsByPrefix("refs/heads/");
    }

    /** Nom court d'une ref de branche ({@code refs/remotes/origin/feat/x} → {@code feat/x}). */
    static String shortBranchName(Ref ref, String remote) {
        String name = ref.getName();
        String remotePrefix = "refs/remotes/" + remote + "/";
        if (name.startsWith(remotePrefix)) {
            return name.substring(remotePrefix.length());
        }
        if (name.startsWith("refs/heads/")) {
            return name.substring("refs/heads/".length());
        }
        return name;
    }

    /** Âge du dernier fetch, pour alerter sur un miroir périmé. */
    static Optional<Duration> fetchAge(LocalRepo repo, Instant now) {
        return Optional.ofNullable(repo.lastFetchAt()).map(t -> Duration.between(t, now));
    }
}
