package com.domwil.xrag.domain.port;

/**
 * Canal de notification de l'instance (batch nocturne, smoke tests).
 * Une implémentation ne doit JAMAIS lever : perdre une notification est
 * acceptable, faire échouer le batch pour une notification ne l'est pas.
 */
public interface Notifier {

    /** Incident nécessitant une intervention (ex. health check en échec, batch abandonné). */
    void alert(String title, String message);

    /** Information de suivi (ex. rapport de fin de batch et smoke test). */
    void info(String title, String message);
}
