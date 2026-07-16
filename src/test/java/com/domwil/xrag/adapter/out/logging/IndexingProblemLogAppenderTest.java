package com.domwil.xrag.adapter.out.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.domwil.xrag.application.IndexingProgressTracker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexingProblemLogAppenderTest {

    private final IndexingProgressTracker tracker = new IndexingProgressTracker();
    private final IndexingProblemLogAppender appender = new IndexingProblemLogAppender(tracker);

    @Test
    void capteUnAvertissementVersLeTracker() {
        tracker.startRun(false);
        tracker.startSource("gitlab-code");
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getLevel()).thenReturn(Level.WARN);
        when(event.getLoggerName()).thenReturn("com.domwil.xrag.adapter.out.gitlab.GitLabConnector");
        when(event.getFormattedMessage()).thenReturn("Fichier illisible pom.xml : ReadTimeoutException");

        appender.append(event);

        var problems = tracker.snapshot().problems();
        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst().level()).isEqualTo("WARN");
        assertThat(problems.getFirst().source()).isEqualTo("gitlab-code");
        assertThat(problems.getFirst().message()).contains("ReadTimeoutException");
    }

    @Test
    void capteUneErreurVersLeTracker() {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getLevel()).thenReturn(Level.ERROR);
        when(event.getLoggerName()).thenReturn("com.domwil.xrag.application.SyncService");
        when(event.getFormattedMessage()).thenReturn("Sync jira en échec");

        appender.append(event);

        assertThat(tracker.snapshot().problems()).hasSize(1);
        assertThat(tracker.snapshot().problems().getFirst().level()).isEqualTo("ERROR");
    }

    @Test
    void ignoreLesNiveauxInferieursAWarn() {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getLevel()).thenReturn(Level.INFO);

        appender.append(event);

        assertThat(tracker.snapshot().problems()).isEmpty();
    }
}
