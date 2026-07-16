package com.domwil.xrag.config;

import ch.qos.logback.classic.Logger;
import com.domwil.xrag.adapter.out.logging.IndexingProblemLogAppender;
import com.domwil.xrag.application.IndexingProgressTracker;
import com.domwil.xrag.application.IndexingStatusService;
import com.domwil.xrag.domain.port.IndexingStatusRepository;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Câblage du dashboard de monitoring : état vivant de l'indexation ({@link IndexingProgressTracker}),
 * service d'agrégation ({@link IndexingStatusService}) et tap de logs qui remonte les problèmes
 * rencontrés au tracker sans coupler les connecteurs.
 */
@Configuration
public class MonitoringConfiguration {

    /** Logger racine de l'application : capte les WARN/ERROR de tous les connecteurs et services. */
    private static final String APPLICATION_LOGGER = "com.domwil.xrag";

    @Bean
    public IndexingProgressTracker indexingProgressTracker() {
        return new IndexingProgressTracker();
    }

    @Bean
    public IndexingStatusService indexingStatusService(IndexingStatusRepository repository,
                                                       IndexingProgressTracker tracker) {
        return new IndexingStatusService(repository, tracker);
    }

    /** Attache l'appender au logger applicatif au démarrage ; retiré proprement à l'arrêt. */
    @Bean(destroyMethod = "stop")
    public IndexingProblemLogAppender indexingProblemLogAppender(IndexingProgressTracker tracker) {
        Logger applicationLogger = (Logger) LoggerFactory.getLogger(APPLICATION_LOGGER);
        IndexingProblemLogAppender appender = new IndexingProblemLogAppender(tracker);
        appender.setContext(applicationLogger.getLoggerContext());
        appender.setName("indexing-problems");
        appender.start();
        applicationLogger.addAppender(appender);
        return appender;
    }
}
