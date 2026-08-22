package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.errand.ports.SyncDiffRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class JdbcSyncDiffRepository implements SyncDiffRepository {

    private final JdbcTemplate jdbc;

    public JdbcSyncDiffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(Instant checkTime, long errandId, String field,
                       String dbValue, String cacheValue, boolean fixed) {
        jdbc.update("""
                INSERT INTO sync_diff (check_time, errand_id, field, db_value, cache_value, fixed)
                VALUES (?, ?, ?, ?, ?, ?)
                """, Timestamp.from(checkTime), errandId, field, dbValue, cacheValue, fixed ? 1 : 0);
    }

    @Override
    public long countSince(Instant since) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_diff WHERE check_time >= ?", Long.class, Timestamp.from(since));
        return n == null ? 0 : n;
    }
}
