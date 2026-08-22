package com.campusdash.domain.errand.ports;

import java.time.Instant;

/**
 * 缓存一致性差异仓储。
 *
 * 这张表正常情况必须为空——有记录就说明缓存链路出了问题
 * （失效丢失、慢读写回旧值、Redis 故障期间的降级残留）。
 * 它同时是排障入口：用户报"详情页状态不对"时先查这里。
 */
public interface SyncDiffRepository {

    void record(Instant checkTime, long errandId, String field,
                String dbValue, String cacheValue, boolean fixed);

    /** 指定时间之后的差异条数（测试与监控用） */
    long countSince(Instant since);
}
