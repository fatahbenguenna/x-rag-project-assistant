package com.domwil.xrag.domain.port;

import com.domwil.xrag.domain.model.GraphQualityMetrics;

/** Mesure de la couverture du graphe sur l'index courant. */
public interface GraphQualityRepository {

    GraphQualityMetrics measure();
}
