package com.campusdash.application.usecase.query;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.ErrandQueryPort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.errand.ports.SyncDiffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 缓存一致性校验 job（架构文档 9.6 的落地）。
 *
 * 每 5 分钟随机抽样 N 个任务，比对 MySQL 与 Redis 缓存的关键字段，
 * 不一致的落 sync_diff 表并主动删缓存修正。
 *
 * ── 定位 ──
 * 这是兜底机制，不是主一致性手段。主链路是 afterCommit 删除 + 延迟双删 + TTL；
 * 本 job 负责检出主链路漏掉的场景（删除失败、Redis 故障期降级残留、未知 bug）。
 * sync_diff 正常必须为空——有记录就要查，它是"缓存链路健康"的哨兵。
 *
 * ── 抽样方式 ──
 * ORDER BY RAND() 在万级数据量没问题；十万级以上要改成按 id 分段随机，
 * 避免全表排序。校园量级无压力，注释留痕。
 */
@Service
public class CacheConsistencyCheckUseCase {

    private static final Logger log = LoggerFactory.getLogger(CacheConsistencyCheckUseCase.class);

    private final ErrandQueryPort queryPort;
    private final ErrandRepository errandRepository;
    private final ErrandCachePort cache;
    private final SyncDiffRepository syncDiffRepository;
    private final int sampleSize;

    public CacheConsistencyCheckUseCase(ErrandQueryPort queryPort,
                                    ErrandRepository errandRepository,
                                    ErrandCachePort cache,
                                    SyncDiffRepository syncDiffRepository,
                                    @Value("${dash.cache.check-sample-size:100}") int sampleSize) {
        this.queryPort = queryPort;
        this.errandRepository = errandRepository;
        this.cache = cache;
        this.syncDiffRepository = syncDiffRepository;
        this.sampleSize = sampleSize;
    }

    /**
     * 执行一轮抽样校验，返回差异数。
     * 调度由 worker 的 CacheConsistencyCheckScheduler 触发；
     * 放在 application 层而不是 worker，是为了让集成测试能直接注入验证。
     */
    public int runOnce() {
        List<Long> ids = queryPort.sampleIds(sampleSize);
        int diffs = 0;
        Instant now = Instant.now();

        for (Long id : ids) {
            // 缓存里没有就不比对：未命中不是不一致（下次读会回源）
            var cached = cache.get(id);
            if (cached.isEmpty() || cached.get().isEmpty()) {
                continue;
            }
            Errand db = errandRepository.findById(id).orElse(null);
            if (db == null) {
                // DB 里没了缓存还在：任务本项目不会物理删除，出现即异常
                syncDiffRepository.record(now, id, "existence", "MISSING", "PRESENT", false);
                diffs++;
                continue;
            }
            String cacheJson = cached.get().payloadJson();
            diffs += compareAndFix(now, id, db, cacheJson);
        }
        if (diffs > 0) {
            log.warn("一致性校验检出 {} 处差异（已落 sync_diff 并修正）", diffs);
        }
        return diffs;
    }

    private int compareAndFix(Instant now, long id, Errand db, String cacheJson) {
        int diffs = 0;
        String cacheStatus = extract(cacheJson, "status");
        String cacheVersion = extract(cacheJson, "version");
        String cacheReward = extract(cacheJson, "rewardCents");

        if (!db.status().name().equals(cacheStatus)) {
            syncDiffRepository.record(now, id, "status", db.status().name(), cacheStatus, true);
            diffs++;
        }
        if (!String.valueOf(db.version()).equals(cacheVersion)) {
            syncDiffRepository.record(now, id, "version", String.valueOf(db.version()), cacheVersion, true);
            diffs++;
        }
        if (!String.valueOf(db.reward().cents()).equals(cacheReward)) {
            syncDiffRepository.record(now, id, "reward_amount", String.valueOf(db.reward().cents()), cacheReward, true);
            diffs++;
        }
        if (diffs > 0) {
            // 修正手段就是删缓存：下次读回源拿到新值。不做"直接改缓存"，
            // 因为改缓存本身也可能写入错误值，删除是最安全的收敛动作
            cache.evict(id);
        }
        return diffs;
    }

    /** 从详情 JSON 提取字段值（字符串字段带引号，数字字段不带） */
    private String extract(String json, String field) {
        String quoted = "\"" + field + "\":\"";
        int i = json.indexOf(quoted);
        if (i >= 0) {
            int start = i + quoted.length();
            return json.substring(start, json.indexOf('"', start));
        }
        String plain = "\"" + field + "\":";
        i = json.indexOf(plain);
        if (i < 0) {
            return null;
        }
        int start = i + plain.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return json.substring(start, end);
    }
}
