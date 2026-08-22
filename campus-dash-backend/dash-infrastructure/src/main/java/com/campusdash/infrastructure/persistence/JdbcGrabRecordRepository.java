package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.grab.model.GrabRecord;
import com.campusdash.domain.grab.ports.GrabRecordRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGrabRecordRepository implements GrabRecordRepository {

    private final JdbcTemplate jdbc;

    public JdbcGrabRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 注意这里用的是普通 INSERT，不是 INSERT IGNORE。
     * 撞唯一索引必须抛 DuplicateKeyException 让上层感知，
     * 因为这两个唯一索引就是防超卖的最后一道防线，把冲突吞掉等于把防线拆了。
     */
    @Override
    public void insert(GrabRecord record) {
        jdbc.update("""
                INSERT INTO grab_record (id, campus_id, errand_id, runner_id, seq, round, result)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                record.id(), record.campusId(), record.errandId(), record.runnerId(),
                record.seq(), record.round(), record.result().name());
    }

    @Override
    public int countGrabbed(long campusId, long errandId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM grab_record WHERE campus_id = ? AND errand_id = ? AND result = 'GRABBED'",
                Integer.class, campusId, errandId);
        return n == null ? 0 : n;
    }
}
