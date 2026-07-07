package com.domwil.xrag.adapter.out.persistence;

import com.domwil.xrag.domain.port.SyncStateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcSyncStateRepository implements SyncStateRepository {

    private final JdbcTemplate jdbc;

    public JdbcSyncStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> lastSync(String source) {
        List<Timestamp> results = jdbc.queryForList(
                "SELECT last_sync FROM sync_state WHERE source = ?", Timestamp.class, source);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst().toInstant());
    }

    @Override
    public void record(String source, Instant syncedAt, String status) {
        jdbc.update("""
                        INSERT INTO sync_state (source, last_sync, last_status) VALUES (?, ?, ?)
                        ON CONFLICT (source) DO UPDATE
                          SET last_sync = EXCLUDED.last_sync, last_status = EXCLUDED.last_status
                        """,
                source, Timestamp.from(syncedAt), status);
    }
}
