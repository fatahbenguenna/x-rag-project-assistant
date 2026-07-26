package com.domwil.xrag.adapter.out.persistence;

import com.domwil.xrag.domain.model.MergeRequestMeta;
import com.domwil.xrag.domain.port.MergeRequestRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Adapter JDBC de la table merge_requests (upsert only). */
@Repository
public class JdbcMergeRequestRepository implements MergeRequestRepository {

    private static final Set<String> SORT_COLUMNS = Set.of("created_at", "updated_at", "merged_at");

    private static final RowMapper<MergeRequestMeta> ROW_MAPPER = (rs, i) -> new MergeRequestMeta(
            rs.getString("id"), rs.getString("project"), rs.getLong("iid"),
            rs.getString("title"), rs.getString("description"), rs.getString("state"),
            rs.getString("author"), rs.getString("source_branch"), rs.getString("target_branch"),
            rs.getString("web_url"),
            Arrays.asList((String[]) rs.getArray("labels").getArray()),
            List.of(),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            toInstant(rs.getTimestamp("merged_at")));

    private final JdbcTemplate jdbc;

    public JdbcMergeRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(Collection<MergeRequestMeta> mergeRequests) {
        jdbc.batchUpdate("""
                        INSERT INTO merge_requests (id, project, iid, title, description, state, author,
                                                    source_branch, target_branch, web_url, labels,
                                                    created_at, updated_at, merged_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET
                            project = EXCLUDED.project, title = EXCLUDED.title,
                            description = EXCLUDED.description, state = EXCLUDED.state,
                            author = EXCLUDED.author, source_branch = EXCLUDED.source_branch,
                            target_branch = EXCLUDED.target_branch, web_url = EXCLUDED.web_url,
                            labels = EXCLUDED.labels, created_at = EXCLUDED.created_at,
                            updated_at = EXCLUDED.updated_at, merged_at = EXCLUDED.merged_at
                        """,
                mergeRequests.stream().map(mr -> new Object[]{
                        mr.id(), mr.project(), mr.iid(), mr.title(), mr.description(), mr.state(),
                        mr.author(), mr.sourceBranch(), mr.targetBranch(), mr.webUrl(),
                        PgArrays.textArray(mr.labels()),
                        toTimestamp(mr.createdAt()), toTimestamp(mr.updatedAt()), toTimestamp(mr.mergedAt())
                }).toList());
    }

    @Override
    public List<MergeRequestMeta> find(String state, String sortColumn, boolean ascending, int limit) {
        String column = SORT_COLUMNS.contains(sortColumn) ? sortColumn : "created_at";
        String sql = "SELECT * FROM merge_requests WHERE (?::text = 'all' OR state = ?) "
                + "ORDER BY " + column + (ascending ? " ASC" : " DESC") + " NULLS LAST LIMIT ?";
        return jdbc.query(sql, ROW_MAPPER, state, state, limit);
    }

    @Override
    public List<MergeRequestMeta> search(List<List<String>> concepts, int limit) {
        if (concepts == null || concepts.isEmpty()) {
            return List.of();
        }
        // Un concept dans le TITRE pèse 3, dans le corps (description/labels/branches) pèse 1 :
        // sinon un doc de suivi récent qui cite tous les noms d'apps supplante la MR qui
        // TRAITE réellement du sujet (match noyé dans une longue description). Match à
        // frontière de mot (« pos » retrouve fps-pos mais pas « compose »). Chaque concept
        // consomme 2 placeholders (regex titre puis regex corps).
        String scoreExpr = concepts.stream()
                .map(c -> "(CASE WHEN base.title_hay ~* ? THEN 3 ELSE 0 END "
                        + "+ CASE WHEN base.body_hay ~* ? THEN 1 ELSE 0 END)")
                .collect(java.util.stream.Collectors.joining(" + "));
        String sql = """
                SELECT scored.* FROM (
                    SELECT base.*, (%s) AS match_score FROM (
                        SELECT m.*,
                            lower(coalesce(m.title, '')) AS title_hay,
                            lower(
                                coalesce(m.description, '') || ' '
                                || coalesce(array_to_string(m.labels, ' '), '') || ' '
                                || coalesce(m.source_branch, '') || ' ' || coalesce(m.target_branch, '')
                            ) AS body_hay
                        FROM merge_requests m
                    ) base
                ) scored
                WHERE scored.match_score > 0
                ORDER BY scored.match_score DESC, scored.updated_at DESC NULLS LAST
                LIMIT ?
                """.formatted(scoreExpr);
        Object[] params = new Object[concepts.size() * 2 + 1];
        int p = 0;
        for (List<String> forms : concepts) {
            String regex = conceptRegex(forms);
            params[p++] = regex; // titre (poids 3)
            params[p++] = regex; // corps (poids 1)
        }
        params[p] = limit;
        return jdbc.query(sql, ROW_MAPPER, params);
    }

    /** Regex POSIX d'un concept : {@code \m(forme1|forme2|…)\M} (frontière de mot, formes échappées). */
    private static String conceptRegex(List<String> forms) {
        String alternation = forms.stream()
                .map(JdbcMergeRequestRepository::escapeRegex)
                .collect(java.util.stream.Collectors.joining("|"));
        return "\\m(" + alternation + ")\\M";
    }

    /** Échappe les métacaractères ERE ; l'espace et le tiret restent littéraux. */
    private static String escapeRegex(String form) {
        return form.replaceAll("([.\\\\+*?\\[\\]^$(){}|])", "\\\\$1");
    }

    @Override
    public Optional<MergeRequestMeta> findByIid(long iid) {
        return jdbc.query("SELECT * FROM merge_requests WHERE iid = ? ORDER BY updated_at DESC LIMIT 1",
                        ROW_MAPPER, iid)
                .stream().findFirst();
    }

    @Override
    public long count(String state) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM merge_requests WHERE (?::text = 'all' OR state = ?)",
                Long.class, state, state);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<Instant> mostRecentUpdate() {
        return Optional.ofNullable(jdbc.queryForObject(
                        "SELECT max(updated_at) FROM merge_requests", Timestamp.class))
                .map(Timestamp::toInstant);
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
