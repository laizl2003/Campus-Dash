package com.campusdash.infrastructure.cache;

import com.campusdash.domain.errand.ports.CacheEvictDelayPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * MQ 关闭时的延迟双删实现：不做第二次删除。
 *
 * 这是明确的降级：只保留 afterCommit 的第一次删除 + TTL 兜底。
 * 集成测试默认走这条路（dash.mq.enabled=false），所以测试里
 * 对"延迟双删覆盖的那个极小窗口"不做断言——那需要真实 MQ，放在联调阶段验证。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "false", matchIfMissing = true)
public class NoopCacheEvictAdapter implements CacheEvictDelayPort {

    @Override
    public void scheduleEvict(long errandId, Instant deliverAt) {
    }
}
