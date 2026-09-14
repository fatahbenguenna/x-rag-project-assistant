package com.domwil.xrag.adapter.out.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse le message d'un commit pour en retrouver la Merge Request d'origine. Pur (aucun
 * accès git) : c'est la connaissance des formats de message des forges qui permet de
 * reconstituer une MR sans API.
 *
 * <p>Formats reconnus :
 * <ul>
 *   <li><b>GitLab</b> (template par défaut) : {@code Merge branch 'src' into 'dst'} puis le
 *       titre de la MR, puis {@code See merge request groupe/projet!153} — le chemin peut
 *       manquer ({@code See merge request !153}, anciennes versions) et la référence
 *       {@code groupe/projet!153} ou l'URL {@code /-/merge_requests/153} est acceptée n'importe
 *       où (templates personnalisés, commit de <b>squash</b> {@code %{title} (%{reference})}) ;</li>
 *   <li><b>GitHub</b> : {@code Merge pull request #12 from owner/branche} puis le titre ;</li>
 *   <li><b>Bitbucket</b> : {@code Merged in branche (pull request #12)} puis le titre ;</li>
 *   <li><b>Azure DevOps</b> : {@code Merged PR 12: titre} ;</li>
 *   <li><b>git natif</b> : {@code Merge branch 'src'} ou {@code Merge branch 'src' into dst}
 *       (guillemets optionnels), sans numéro — reconstitution partielle.</li>
 * </ul>
 * Les fusions de synchronisation ({@code Merge branch 'main' of https://…}, {@code Merge
 * remote-tracking branch 'origin/main'}, source = cible) ne sont pas des MRs : vide.
 */
public final class MergeCommitMessageParser {

    /** Résultat du parsing ; {@code iid} vaut 0 quand la forge n'a pas laissé de numéro. */
    public record ParsedMerge(
            String hosting,          // gitlab | github | bitbucket | azure | git
            long iid,
            String reference,        // chemin complet "groupe/sous-groupe/projet" (GitLab) ou null
            String sourceBranch,
            String targetBranch,     // null si absent du message
            String title,            // null si le message n'en porte pas (repli sur la branche)
            String description       // corps restant (trailers inclus : ils portent les clés Jira)
    ) {
    }

    /** Référence de MR GitLab trouvée dans un message (commit de fusion ou de squash). */
    public record MergeReference(long iid, String reference) {
    }

    private static final Pattern GIT_HEAD = Pattern.compile(
            "^Merge branch '?(?<src>[^'\\s]+)'?(?: into '?(?<dst>[^'\\s]+)'?)?\\s*$");
    private static final Pattern GITHUB_HEAD = Pattern.compile(
            "^Merge pull request #(?<iid>\\d+) from (?<src>\\S+)\\s*$");
    private static final Pattern BITBUCKET_HEAD = Pattern.compile(
            "^Merged in (?<src>\\S+) \\(pull request #(?<iid>\\d+)\\)\\s*$");
    private static final Pattern AZURE_HEAD = Pattern.compile(
            "^Merged PR (?<iid>\\d+): (?<title>.+?)\\s*$");
    /** Référence GitLab : {@code grp/sub/proj!153}, {@code !153}, ou URL {@code /-/merge_requests/153}. */
    private static final Pattern GITLAB_REFERENCE = Pattern.compile(
            // chemin complet (au moins un « / ») ou rien du tout : « Wow!2024 » n'est pas une référence
            "(?:(?<=^)|(?<=[\\s(\\[:]))(?<ref>[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+)?!(?<iid>\\d{1,7})\\b"
                    + "|/-/merge_requests/(?<iidUrl>\\d{1,7})\\b");
    /** Fusions de synchronisation (git pull, remote-tracking) : jamais des MRs. */
    private static final Pattern SYNC_MERGE = Pattern.compile(
            "^Merge (?:remote-tracking branch|branch '?[^'\\s]+'? of |branches )");
    /** Lignes de trailer qui ne sont pas un titre. */
    private static final Pattern TRAILER = Pattern.compile(
            "^(?:See merge request|Closes?|Close[sd]?|Fix(?:es|ed)?|Resolve[sd]?|Related(?: to)?|Implements?|"
                    + "Co-authored-by|Signed-off-by|Change-Id|Reviewed-by|Approved-by|Merged-by|Changelog|"
                    + "Merge-request|This reverts commit)\\b.*",
            Pattern.CASE_INSENSITIVE);
    /** Suffixe de référence d'un titre de squash : {@code Titre (grp/proj!153)}. */
    private static final Pattern TITLE_REFERENCE_SUFFIX = Pattern.compile(
            "\\s*\\(?[A-Za-z0-9_./-]*!\\d{1,7}\\)?\\s*$");

    private MergeCommitMessageParser() {
    }

    /** Message d'un commit de fusion (≥ 2 parents) → MR ; vide si ce n'est pas une MR. */
    public static Optional<ParsedMerge> parse(String fullMessage) {
        if (fullMessage == null || fullMessage.isBlank()) {
            return Optional.empty();
        }
        List<String> lines = normalizedLines(fullMessage);
        String head = lines.getFirst();
        if (SYNC_MERGE.matcher(head).find()) {
            return Optional.empty();
        }
        List<String> body = lines.subList(1, lines.size());

        Matcher m = GIT_HEAD.matcher(head);
        if (m.matches()) {
            String src = m.group("src");
            String dst = m.group("dst");
            if (isSyncMerge(src, dst)) {
                return Optional.empty();
            }
            Optional<MergeReference> ref = reference(fullMessage);
            return Optional.of(new ParsedMerge(ref.isPresent() ? "gitlab" : "git",
                    ref.map(MergeReference::iid).orElse(0L), ref.map(MergeReference::reference).orElse(null),
                    src, dst, firstTitleLine(body), remainingBody(body)));
        }
        m = GITHUB_HEAD.matcher(head);
        if (m.matches()) {
            String src = m.group("src");
            // GitHub préfixe la branche par le propriétaire du fork : "owner/feature/x" -> "feature/x"
            int slash = src.indexOf('/');
            if (slash > 0) {
                src = src.substring(slash + 1);
            }
            return Optional.of(new ParsedMerge("github", Long.parseLong(m.group("iid")), null, src, null,
                    firstTitleLine(body), remainingBody(body)));
        }
        m = BITBUCKET_HEAD.matcher(head);
        if (m.matches()) {
            return Optional.of(new ParsedMerge("bitbucket", Long.parseLong(m.group("iid")), null,
                    m.group("src"), null, firstTitleLine(body), remainingBody(body)));
        }
        m = AZURE_HEAD.matcher(head);
        if (m.matches()) {
            return Optional.of(new ParsedMerge("azure", Long.parseLong(m.group("iid")), null, null, null,
                    m.group("title"), joinLines(body)));
        }
        // Template GitLab personnalisé : pas de « Merge branch » mais une référence quelque part
        Optional<MergeReference> ref = reference(fullMessage);
        if (ref.isPresent()) {
            return Optional.of(new ParsedMerge("gitlab", ref.get().iid(), ref.get().reference(), null, null,
                    stripReferenceSuffix(head), joinLines(body)));
        }
        return Optional.empty();
    }

    /**
     * Référence de MR GitLab présente dans un message, quel que soit le nombre de parents :
     * un commit de squash {@code %{title} (%{reference})} porte ainsi son numéro de MR.
     */
    public static Optional<MergeReference> reference(String message) {
        if (message == null) {
            return Optional.empty();
        }
        Matcher m = GITLAB_REFERENCE.matcher(message.replace("\r\n", "\n"));
        if (!m.find()) {
            return Optional.empty();
        }
        if (m.group("iidUrl") != null) {
            return Optional.of(new MergeReference(Long.parseLong(m.group("iidUrl")), null));
        }
        String ref = m.group("ref");
        return Optional.of(new MergeReference(Long.parseLong(m.group("iid")),
                ref == null || ref.isBlank() ? null : ref));
    }

    /** Titre d'un commit de squash sans son suffixe de référence ({@code Titre (grp/proj!153)} → {@code Titre}). */
    public static String stripReferenceSuffix(String subject) {
        if (subject == null) {
            return null;
        }
        String stripped = TITLE_REFERENCE_SUFFIX.matcher(subject).replaceFirst("").strip();
        return stripped.isEmpty() ? subject.strip() : stripped;
    }

    /**
     * Titre lisible dérivé d'un nom de branche : préfixe de type retiré, séparateurs en espaces,
     * clé Jira préservée ({@code feature/PASS-12-suivi_commande} → {@code PASS-12 suivi commande}).
     */
    public static String humanizeBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            return "";
        }
        String name = branch;
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        // un tiret est un séparateur sauf entre lettres/chiffres majuscules et des chiffres (clé Jira)
        return name.replaceAll("_+", " ")
                .replaceAll("(?<![A-Z0-9])-|-(?!\\d)", " ")
                .replaceAll("\\s+", " ").strip();
    }

    /** Une fusion « source = cible » (git pull, synchronisation) n'est pas une MR. */
    private static boolean isSyncMerge(String src, String dst) {
        if (dst == null) {
            return false;
        }
        String s = src.toLowerCase(Locale.ROOT);
        if (s.startsWith("origin/")) {
            s = s.substring("origin/".length());
        }
        return s.equals(dst.toLowerCase(Locale.ROOT));
    }

    private static List<String> normalizedLines(String message) {
        var lines = new ArrayList<String>();
        for (String line : message.replace("\r\n", "\n").replace('\r', '\n').strip().split("\n")) {
            lines.add(line.strip());
        }
        return lines;
    }

    private static String firstTitleLine(List<String> body) {
        for (String line : body) {
            if (!line.isEmpty() && !TRAILER.matcher(line).matches()) {
                return line;
            }
        }
        return null;
    }

    /** Corps sans la ligne de titre (déjà extraite) ; les trailers restent (clés Jira). */
    private static String remainingBody(List<String> body) {
        String title = firstTitleLine(body);
        var kept = new ArrayList<String>();
        boolean titleSkipped = title == null;
        for (String line : body) {
            if (!titleSkipped && line.equals(title)) {
                titleSkipped = true;
                continue;
            }
            kept.add(line);
        }
        return joinLines(kept);
    }

    private static String joinLines(List<String> lines) {
        return String.join("\n", lines).strip().replaceAll("\\n{3,}", "\n\n");
    }
}
