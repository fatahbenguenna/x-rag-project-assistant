package com.domwil.xrag.domain.port;

import java.util.List;
import java.util.Optional;

/** Connecteurs effectivement activés par la configuration d'équipe. */
public record ConnectorRegistry(
        List<SourceConnector> documentConnectors,
        Optional<MergeRequestConnector> mergeRequestConnector
) {

    public ConnectorRegistry {
        documentConnectors = List.copyOf(documentConnectors);
    }
}
