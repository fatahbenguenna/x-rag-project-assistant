package com.domwil.xrag.domain.port;

import com.domwil.xrag.domain.model.MergeRequestMeta;

import java.time.Instant;
import java.util.List;

/** Port de récolte des métadonnées MR (alimente la table merge_requests et les tools). */
public interface MergeRequestConnector {

    /** MRs mises à jour après {@code since} ({@code null} = toutes), tous états confondus. */
    List<MergeRequestMeta> fetchUpdatedAfter(Instant since);
}
