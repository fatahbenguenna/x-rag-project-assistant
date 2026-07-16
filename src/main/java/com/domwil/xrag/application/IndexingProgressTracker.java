package com.domwil.xrag.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * État partagé thread-safe de l'indexation en cours, alimenté par {@link SyncService}
 * (cycle de vie des sources) et par le tap de logs (problèmes rencontrés). Lu par le
 * dashboard de monitoring via {@link IndexingStatusService}.
 *
 * <p>La synchronisation est portée par un unique moniteur : les mutations sont peu
 * fréquentes (une sync par source) et {@code snapshot()} doit voir un état cohérent
 * même appelé depuis le thread WebFlux pendant qu'un {@code TaskExecutor} indexe.
 */
public class IndexingProgressTracker {

    static final int MAX_PROBLEMS = 100;

    private final Object lock = new Object();
    private final Map<String, SourceProgress> sources = new LinkedHashMap<>();
    private final Deque<Problem> problems = new ArrayDeque<>();

    private boolean running;
    private boolean fullRun;
    private Instant runStartedAt;
    private Duration lastRunDuration;
    private String currentSource;

    /** Démarre un run d'indexation : ardoise propre (sources et problèmes réinitialisés). */
    public void startRun(boolean full) {
        synchronized (lock) {
            running = true;
            fullRun = full;
            runStartedAt = Instant.now();
            currentSource = null;
            sources.clear();
            problems.clear();
        }
    }

    /** Marque le début de la synchronisation d'une source. */
    public void startSource(String source) {
        synchronized (lock) {
            currentSource = source;
            sources.put(source, SourceProgress.started(source, Instant.now()));
        }
    }

    /** Clôture une source avec son statut final et ses compteurs (indexés/total). */
    public void finishSource(String source, String status, int indexed, int total) {
        synchronized (lock) {
            SourceProgress previous = sources.get(source);
            Instant startedAt = previous != null ? previous.startedAt() : Instant.now();
            sources.put(source, new SourceProgress(source, startedAt, Instant.now(), status, indexed, total));
            if (source.equals(currentSource)) {
                currentSource = null;
            }
        }
    }

    /** Enregistre un problème (WARN/ERROR) stampé avec la source courante ; liste bornée. */
    public void recordProblem(String level, String logger, String message) {
        synchronized (lock) {
            problems.addFirst(new Problem(Instant.now(), level, currentSource, logger, message));
            while (problems.size() > MAX_PROBLEMS) {
                problems.removeLast();
            }
        }
    }

    /** Termine le run : calcule la durée totale et repasse à l'état au repos. */
    public void finishRun() {
        synchronized (lock) {
            running = false;
            if (runStartedAt != null) {
                lastRunDuration = Duration.between(runStartedAt, Instant.now());
            }
            currentSource = null;
        }
    }

    /** Vue immuable et cohérente de l'état courant, destinée au dashboard. */
    public ProgressSnapshot snapshot() {
        synchronized (lock) {
            Long elapsed = running && runStartedAt != null
                    ? Duration.between(runStartedAt, Instant.now()).toSeconds() : null;
            Long lastDuration = lastRunDuration != null ? lastRunDuration.toSeconds() : null;
            return new ProgressSnapshot(
                    running, fullRun, currentSource, runStartedAt, elapsed, lastDuration,
                    List.copyOf(sources.values()), List.copyOf(problems));
        }
    }

    /** Instantané de l'indexation en cours (état vivant, en mémoire). */
    public record ProgressSnapshot(
            boolean running,
            boolean fullRun,
            String currentSource,
            Instant runStartedAt,
            Long elapsedSeconds,
            Long lastRunDurationSeconds,
            List<SourceProgress> sources,
            List<Problem> problems) {
    }

    /** Avancement d'une source : bornes temporelles, statut, compteurs. */
    public record SourceProgress(
            String source,
            Instant startedAt,
            Instant finishedAt,
            String status,
            int indexed,
            int total) {

        static SourceProgress started(String source, Instant startedAt) {
            return new SourceProgress(source, startedAt, null, "EN COURS", 0, 0);
        }
    }

    /** Problème rencontré pendant l'indexation (fichier illisible, timeout GitLab...). */
    public record Problem(
            Instant at,
            String level,
            String source,
            String logger,
            String message) {
    }
}
