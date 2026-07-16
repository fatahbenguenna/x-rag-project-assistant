package com.domwil.xrag.application;

import com.domwil.xrag.application.IndexingProgressTracker.ProgressSnapshot;
import com.domwil.xrag.application.IndexingProgressTracker.SourceProgress;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class IndexingProgressTrackerTest {

    private final IndexingProgressTracker tracker = new IndexingProgressTracker();

    @Test
    void snapshotInitialSansIndexationEnCours() {
        ProgressSnapshot snapshot = tracker.snapshot();

        assertThat(snapshot.running()).isFalse();
        assertThat(snapshot.currentSource()).isNull();
        assertThat(snapshot.elapsedSeconds()).isNull();
        assertThat(snapshot.sources()).isEmpty();
        assertThat(snapshot.problems()).isEmpty();
    }

    @Test
    void demarrerUnRunMarqueLIndexationCommeEnCours() {
        tracker.startRun(true);

        ProgressSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.running()).isTrue();
        assertThat(snapshot.fullRun()).isTrue();
        assertThat(snapshot.runStartedAt()).isNotNull();
        assertThat(snapshot.elapsedSeconds()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    @Test
    void distingueLeRunIncrementalDuRunComplet() {
        tracker.startRun(false);

        assertThat(tracker.snapshot().fullRun()).isFalse();
    }

    @Test
    void suivreUneSourceEnregistreSesCompteursEtSonStatut() {
        tracker.startRun(false);
        tracker.startSource("confluence");
        tracker.finishSource("confluence", "OK", 118, 120);

        ProgressSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.currentSource()).isNull();
        assertThat(snapshot.sources()).hasSize(1);
        SourceProgress progress = snapshot.sources().getFirst();
        assertThat(progress.source()).isEqualTo("confluence");
        assertThat(progress.status()).isEqualTo("OK");
        assertThat(progress.indexed()).isEqualTo(118);
        assertThat(progress.total()).isEqualTo(120);
        assertThat(progress.startedAt()).isNotNull();
        assertThat(progress.finishedAt()).isNotNull();
    }

    @Test
    void uneSourceEnCoursExposeLaSourceCourante() {
        tracker.startRun(false);
        tracker.startSource("gitlab-code");

        ProgressSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.currentSource()).isEqualTo("gitlab-code");
        assertThat(snapshot.sources()).hasSize(1);
        assertThat(snapshot.sources().getFirst().finishedAt()).isNull();
    }

    @Test
    void terminerLeRunCalculeLaDureeEtArreteLIndexation() {
        tracker.startRun(false);
        tracker.startSource("jira");
        tracker.finishSource("jira", "OK", 76, 76);
        tracker.finishRun();

        ProgressSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.running()).isFalse();
        assertThat(snapshot.currentSource()).isNull();
        assertThat(snapshot.elapsedSeconds()).isNull();
        assertThat(snapshot.lastRunDurationSeconds()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    @Test
    void enregistrerUnProblemeLeStampeAvecLaSourceCourante() {
        tracker.startRun(false);
        tracker.startSource("gitlab-code");
        tracker.recordProblem("WARN", "GitLabConnector", "Fichier illisible pom.xml : ReadTimeoutException");

        var problems = tracker.snapshot().problems();
        assertThat(problems).hasSize(1);
        var problem = problems.getFirst();
        assertThat(problem.level()).isEqualTo("WARN");
        assertThat(problem.source()).isEqualTo("gitlab-code");
        assertThat(problem.logger()).isEqualTo("GitLabConnector");
        assertThat(problem.message()).contains("ReadTimeoutException");
        assertThat(problem.at()).isNotNull();
    }

    @Test
    void lesProblemesSontBornesEtLesPlusRecentsEnTete() {
        tracker.startRun(false);
        for (int i = 0; i < IndexingProgressTracker.MAX_PROBLEMS + 20; i++) {
            tracker.recordProblem("WARN", "L", "probleme " + i);
        }

        var problems = tracker.snapshot().problems();
        assertThat(problems).hasSize(IndexingProgressTracker.MAX_PROBLEMS);
        assertThat(problems.getFirst().message()).isEqualTo("probleme " + (IndexingProgressTracker.MAX_PROBLEMS + 19));
    }

    @Test
    void unNouveauRunRepartDUneArdoisePropre() {
        tracker.startRun(false);
        tracker.startSource("jira");
        tracker.finishSource("jira", "OK", 1, 1);
        tracker.recordProblem("ERROR", "L", "vieille erreur");
        tracker.finishRun();

        tracker.startRun(true);

        ProgressSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.sources()).isEmpty();
        assertThat(snapshot.problems()).isEmpty();
        assertThat(snapshot.lastRunDurationSeconds()).isNotNull();
    }

    @Test
    void accesConcurrentSansPerteNiIncoherence() throws InterruptedException {
        int threads = 8;
        int perThread = 200;
        tracker.startRun(false);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        tracker.recordProblem("WARN", "L", "x");
                        tracker.snapshot();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(tracker.snapshot().problems()).hasSize(IndexingProgressTracker.MAX_PROBLEMS);
    }
}
