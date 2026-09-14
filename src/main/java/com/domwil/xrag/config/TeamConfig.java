package com.domwil.xrag.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;

/**
 * Contrat de configuration d'une instance d'équipe, chargé depuis team-config.yml
 * (voir team-config.example.yml). Tout est piloté ici : aucun nom de projet en dur.
 */
@ConfigurationProperties
@Validated
public record TeamConfig(
        @NotBlank String team,
        @NotNull @Valid Llm llm,
        @NotNull @Valid Sources sources,
        Map<String, List<String>> aliases,
        Map<String, List<String>> synonyms,
        Retrieval retrieval,
        Eval eval,
        Schedule schedule,
        @Valid Extractors extractors
) {

    public TeamConfig {
        aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
        synonyms = synonyms == null ? Map.of() : Map.copyOf(synonyms);
        retrieval = retrieval == null ? new Retrieval(null, null, null) : retrieval;
        eval = eval == null ? new Eval(List.of()) : eval;
        schedule = schedule == null ? new Schedule(null) : schedule;
        extractors = extractors == null ? new Extractors(true, true, false, false, null) : extractors;
    }

    /**
     * @param numCtx      fenêtre de contexte Ollama — le défaut serveur (2048-4096) tronque
     *                    silencieusement le prompt RAG ; 8192 couvre large. Sans objet pour
     *                    un provider distant. Mémoire KV cache ≈ proportionnelle : voir
     *                    OLLAMA_KV_CACHE_TYPE dans .env.example sur machine contrainte.
     * @param temperature bas = factuel (défaut 0.1) — appliqué à TOUS les providers (la
     *                    température par défaut de Gemini (~1.0) perdrait le cadrage factuel)
     */
    public record Llm(
            @NotBlank String provider,
            @NotBlank String model,
            String fallbackModel,
            String embeddingModel,
            @Min(1) Integer numCtx,
            @DecimalMin("0.0") Double temperature
    ) {
        public Llm {
            embeddingModel = embeddingModel == null ? "bge-m3" : embeddingModel;
            numCtx = numCtx == null ? 8192 : numCtx;
            temperature = temperature == null ? 0.1 : temperature;
        }
    }

    /**
     * Le code et les MRs viennent soit de l'API GitLab ({@code gitlab}), soit de dépôts git
     * LOCAUX montés en volume ({@code git}, sans token : politique cyber interdisant l'API,
     * poste hors réseau…). Les deux sont exclusifs : mêmes clés de chunk, même table MR.
     */
    public record Sources(
            @Valid Confluence confluence,
            @Valid Gitlab gitlab,
            @Valid Git git,
            @Valid Jira jira
    ) {
        /** Déclaratif (pas d'exception au binding) : l'erreur sort dans le rapport de validation unique. */
        @AssertTrue(message = "sources.gitlab (API) et sources.git (dépôts locaux) sont exclusifs : "
                + "ne garder qu'un seul mode pour le code et les MRs")
        public boolean isSingleCodeSource() {
            return gitlab == null || git == null;
        }
    }

    public record Confluence(@NotBlank String baseUrl, @NotEmpty List<String> spaces) {
    }

    public record Gitlab(@NotBlank String baseUrl, @NotBlank String group, List<String> branches) {
        public Gitlab {
            branches = branches == null || branches.isEmpty() ? List.of("main") : List.copyOf(branches);
        }
    }

    public record Jira(@NotBlank String baseUrl, @NotEmpty List<String> projects) {
    }

    /**
     * Dépôts git LOCAUX (mode sans API GitLab) : lus en place via JGit, jamais fetchés par
     * l'application — la fraîcheur est celle des {@code git fetch} du poste (scripts/git-fetch-all.sh).
     *
     * @param root          dossier (dans le conteneur) contenant les clones — monté par
     *                      {@code GIT_REPOS_DIR:/repos:ro} dans docker-compose
     * @param repos         sous-chemins explicites relatifs à {@code root} ; vide = découverte
     *                      automatique (profondeur 4 : {@code root/projet} … {@code root/grp/sous-grp/…/projet},
     *                      clones de travail ou dépôts bare)
     * @param branches      branches cibles : code indexé ET historique fouillé pour les MRs
     * @param remote        remote dont les refs de suivi ({@code refs/remotes/<remote>/<branche>})
     *                      sont lues en priorité — les plus fraîches après un fetch, sans checkout ;
     *                      repli sur {@code refs/heads/<branche>} (dépôt bare/mirror)
     * @param webBaseUrl    optionnel : origine web de la forge pour les liens cliquables (blob, MR),
     *                      ex. {@code https://gitlab.example.com} ; défaut : déduite de l'URL du
     *                      remote ({@code git@hote:grp/proj.git} → {@code https://hote})
     * @param maxFileKb     fichiers plus gros ignorés (vendored, minifiés, fixtures)
     * @param mergeRequests réglages de la reconstitution des MRs depuis l'historique
     */
    public record Git(@NotBlank String root, List<String> repos, List<String> branches, String remote,
                      String webBaseUrl, @Min(1) Integer maxFileKb, @Valid GitMergeRequests mergeRequests) {
        public Git {
            repos = repos == null ? List.of() : List.copyOf(repos);
            branches = branches == null || branches.isEmpty() ? List.of("main") : List.copyOf(branches);
            remote = remote == null || remote.isBlank() ? "origin" : remote.strip();
            webBaseUrl = webBaseUrl == null || webBaseUrl.isBlank() ? null : stripTrailingSlash(webBaseUrl.strip());
            maxFileKb = maxFileKb == null ? 512 : maxFileKb;
            mergeRequests = mergeRequests == null
                    ? new GitMergeRequests(null, null, null, null, null, null, null, null) : mergeRequests;
        }

        private static String stripTrailingSlash(String url) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }

    /**
     * Reconstitution des MRs depuis l'historique git (aucune API) — trois voies cumulables :
     *
     * @param fromMergeCommits    MRs FUSIONNÉES retrouvées dans les commits de fusion (GitLab
     *                            « See merge request !153 », GitHub, Bitbucket, Azure, git natif) —
     *                            numéro, titre, branches, fichiers, auteur, dates ; un commit de
     *                            squash portant la référence {@code (groupe/projet!153)} compte aussi
     * @param fromLinearCommits   MRs « synthétiques » : séries contiguës de commits directs partageant
     *                            la même clé Jira (fast-forward / squash sans référence) ; les commits
     *                            sans clé Jira sont ignorés
     * @param fromOpenBranches    branches distantes non fusionnées = MRs OUVERTES (heuristique : une
     *                            branche poussée n'a pas forcément de MR) ; l'ensemble est autoritaire —
     *                            celles qui disparaissent (fusionnées, supprimées) sont retirées
     * @param openBranchesExclude motifs glob de branches jamais considérées comme MR ouverte
     *                            (branches longues, bots)
     * @param jiraProjects        clés de projets Jira attendues dans les commits (défaut :
     *                            {@code sources.jira.projects}) — permet la détection insensible à la
     *                            casse ({@code pass-12}) et écarte les faux positifs ({@code SHA-256}) ;
     *                            vide et sans Jira configuré = regex générique majuscule
     * @param rescanDays          fenêtre re-balayée au-delà du curseur {@code updated_after} : un
     *                            fetch tardif ramène des fusions déjà anciennes (upsert idempotent)
     * @param maxChangedFiles     plafond des fichiers touchés retenus par MR (arêtes MODIFIES)
     * @param maxCommitsListed    commits listés dans la description reconstituée
     */
    public record GitMergeRequests(Boolean fromMergeCommits, Boolean fromLinearCommits, Boolean fromOpenBranches,
                                   List<String> openBranchesExclude, List<String> jiraProjects,
                                   @Min(0) Integer rescanDays, @Min(1) Integer maxChangedFiles,
                                   @Min(1) Integer maxCommitsListed) {
        public static final List<String> DEFAULT_OPEN_BRANCHES_EXCLUDE =
                List.of("release/*", "renovate/*", "dependabot/*", "revert-*", "cherry-pick-*");

        public GitMergeRequests {
            fromMergeCommits = fromMergeCommits == null || fromMergeCommits;
            fromLinearCommits = fromLinearCommits == null || fromLinearCommits;
            fromOpenBranches = fromOpenBranches == null || fromOpenBranches;
            openBranchesExclude = openBranchesExclude == null
                    ? DEFAULT_OPEN_BRANCHES_EXCLUDE : List.copyOf(openBranchesExclude);
            jiraProjects = jiraProjects == null ? List.of() : List.copyOf(jiraProjects);
            rescanDays = rescanDays == null ? 30 : rescanDays;
            maxChangedFiles = maxChangedFiles == null ? 500 : maxChangedFiles;
            maxCommitsListed = maxCommitsListed == null ? 50 : maxCommitsListed;
        }
    }

    /**
     * Réglages du retrieval injecté au prompt. Défauts calibrés pour num_ctx=8192 :
     * 8 chunks montrés en entier (1800 car. = la taille max d'un chunk — l'ancienne
     * troncature à 900 cachait 62-81 % du contenu au LLM, revue 2026-07). Repli si la
     * latence CPU se dégrade : chunk-limit: 6, chunk-excerpt-chars: 1600.
     */
    public record Retrieval(Integer chunkLimit, Integer chunkExcerptChars, Reranker reranker) {
        public Retrieval {
            chunkLimit = chunkLimit == null ? 8 : chunkLimit;
            chunkExcerptChars = chunkExcerptChars == null ? 1800 : chunkExcerptChars;
            reranker = reranker == null ? new Reranker(null, null, null, null, null, null, null) : reranker;
        }
    }

    /**
     * Reranker cross-encoder ONNX in-process (bge-reranker-v2-m3 int8). DÉSACTIVÉ par
     * défaut : coût CPU (~3-8 s pour 40 paires sur la cible) + modèle de 571 Mo à
     * télécharger (bootstrap.sh, volume reranker-models). {@code candidates} = taille du
     * vivier reclassé.
     */
    public record Reranker(Boolean enabled, String modelPath, String modelFile,
                           Integer maxLength, Integer candidates, Integer batchSize,
                           Integer intraOpThreads) {
        public Reranker {
            enabled = enabled != null && enabled;
            modelPath = modelPath == null ? "/models/reranker" : modelPath;
            modelFile = modelFile == null ? "model_quantized" : modelFile;
            maxLength = maxLength == null ? 512 : maxLength;
            candidates = candidates == null ? 40 : candidates;
            batchSize = batchSize == null ? 16 : batchSize;
            intraOpThreads = intraOpThreads == null
                    ? Runtime.getRuntime().availableProcessors() : intraOpThreads;
        }
    }

    /**
     * Cas d'évaluation du retrieval (recall@k) : pour chaque question canonique, la
     * source attendue est identifiée par une sous-chaîne (insensible à la casse) de son
     * path ou de son titre. Mesuré sans LLM (rapide, objectif) — prérequis au calibrage
     * des poids de la recherche hybride et à la mesure du gain d'un reranker.
     */
    public record Eval(List<EvalCase> cases) {
        public Eval {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }

    public record EvalCase(String question, String expected) {
    }

    public record Schedule(String nightly) {
        public Schedule {
            nightly = nightly == null ? "0 0 2 * * *" : nightly;
        }
    }

    /**
     * @param llm enrichissement LLM nocturne du graphe (décision d'architecture n°10) : à
     *            n'activer que si l'éval de qualité montre des trous (chunks non rattachés)
     */
    /**
     * @param llm             enrichissement LLM nocturne du graphe (décision n°10)
     * @param llmMaxDocsPerNight plafond de documents topic-enrichis par nuit (budget de
     *                           temps du batch — le backlog restant suit les nuits suivantes)
     */
    public record Extractors(boolean java, boolean typescript, boolean python, boolean llm,
                             @Min(1) Integer llmMaxDocsPerNight) {
        public Extractors {
            llmMaxDocsPerNight = llmMaxDocsPerNight == null ? 150 : llmMaxDocsPerNight;
        }
    }
}
