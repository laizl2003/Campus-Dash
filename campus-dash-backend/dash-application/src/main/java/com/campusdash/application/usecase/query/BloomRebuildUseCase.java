package com.campusdash.application.usecase.query;

import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.ErrandQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 布隆重建：把存量任务 id 全量灌入布隆过滤器。
 *
 * 触发场景：
 *   1. worker 启动时（BloomRebuildJob）
 *   2. 绕过发布用例的批量造数之后（数据迁移、压测 seed、DBA 修数）——
 *      这是 P5 实测确认的运维约束：布隆只认"登记过的 id"，
 *      绕过应用写入的数据必须补登记，否则会被防穿透逻辑误杀
 */
@Service
public class BloomRebuildUseCase {

    private static final Logger log = LoggerFactory.getLogger(BloomRebuildUseCase.class);

    private final ErrandQueryPort queryPort;
    private final ErrandCachePort cache;

    public BloomRebuildUseCase(ErrandQueryPort queryPort, ErrandCachePort cache) {
        this.queryPort = queryPort;
        this.cache = cache;
    }

    public int rebuild() {
        List<Long> ids = queryPort.sampleIds(Integer.MAX_VALUE);
        for (Long id : ids) {
            cache.registerExisting(id);
        }
        log.info("布隆重建完成，灌入 {} 个任务 id", ids.size());
        return ids.size();
    }
}
