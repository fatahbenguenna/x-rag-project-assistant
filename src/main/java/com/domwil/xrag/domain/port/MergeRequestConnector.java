package com.domwil.xrag.domain.port;

import com.domwil.xrag.domain.model.MergeRequestMeta;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Port de récolte des métadonnées MR (alimente la table merge_requests et les tools). */
public interface MergeRequestConnector {

    /** MRs mises à jour après {@code since} ({@code null} = toutes), tous états confondus. */
    List<MergeRequestMeta> fetchUpdatedAfter(Instant since);

    /**
     * Ensemble AUTORITAIRE des ids des MRs actuellement ouvertes, quand la source sait l'énumérer
     * en totalité (dépôts git locaux : branches non fusionnées). Après une récolte réussie, les
     * MRs {@code opened} absentes de cet ensemble sont supprimées de la table et du graphe
     * (branche fusionnée ou abandonnée) — seule exception au « upsert only », sinon « quelle MR
     * ouverte est la plus vieille ? » répondrait faux. Vide (défaut, API GitLab) = l'état
     * {@code closed}/{@code merged} arrive par le delta, rien à réconcilier. Doit rendre vide
     * dès que l'énumération est incomplète (dépôt illisible) : jamais de suppression sur un
     * instantané partiel.
     */
    default Optional<Set<String>> currentlyOpenIds() {
        return Optional.empty();
    }
}
