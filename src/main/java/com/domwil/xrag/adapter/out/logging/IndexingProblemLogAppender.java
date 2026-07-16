package com.domwil.xrag.adapter.out.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.domwil.xrag.application.IndexingProgressTracker;

/**
 * Adapter de sortie qui tape le flux de logs de l'application pour alimenter le
 * dashboard : les WARN/ERROR émis pendant l'indexation (fichiers illisibles,
 * {@code ReadTimeoutException} GitLab, extractions en échec...) sont remontés au
 * {@link IndexingProgressTracker} sans coupler les connecteurs au monitoring.
 */
public class IndexingProblemLogAppender extends AppenderBase<ILoggingEvent> {

    private final IndexingProgressTracker tracker;

    public IndexingProblemLogAppender(IndexingProgressTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
            tracker.recordProblem(event.getLevel().toString(), event.getLoggerName(), event.getFormattedMessage());
        }
    }
}
