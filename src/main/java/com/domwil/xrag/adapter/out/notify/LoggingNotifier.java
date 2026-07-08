package com.domwil.xrag.adapter.out.notify;

import com.domwil.xrag.domain.port.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fallback quand aucun webhook n'est configuré : tout passe dans les logs. */
public class LoggingNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);

    @Override
    public void alert(String title, String message) {
        log.error("ALERTE — {} : {}", title, message);
    }

    @Override
    public void info(String title, String message) {
        log.info("{} : {}", title, message);
    }
}
