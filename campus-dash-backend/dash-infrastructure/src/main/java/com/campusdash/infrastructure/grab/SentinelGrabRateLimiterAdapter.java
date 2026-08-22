package com.campusdash.infrastructure.grab;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.campusdash.domain.grab.ports.GrabRateLimiterPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * P7 生产口径热点参数限流。
 *
 * Sentinel 的资源维度是抢单入口，参数维度是 errandId，因此一个爆款任务被限流时，
 * 不会误伤其他任务的抢单请求。
 */
@Primary
@Component
@ConditionalOnProperty(name = "dash.grab.limit.type", havingValue = "sentinel")
public class SentinelGrabRateLimiterAdapter implements GrabRateLimiterPort {

    private final boolean enabled;
    private final String resourceName;

    public SentinelGrabRateLimiterAdapter(
            @Value("${dash.grab.limit.enabled:true}") boolean enabled,
            @Value("${dash.grab.limit.per-second:500}") int permitsPerSecond,
            @Value("${dash.grab.limit.resource:campus_dash_grab}") String resourceName) {
        this.enabled = enabled;
        this.resourceName = resourceName;
        installRule(resourceName, Math.max(1, permitsPerSecond));
    }

    @Override
    public boolean tryPass(long errandId, long runnerId) {
        if (!enabled) {
            return true;
        }
        Entry entry = null;
        try {
            entry = SphU.entry(resourceName, EntryType.IN, 1, errandId);
            return true;
        } catch (BlockException e) {
            return false;
        } finally {
            if (entry != null) {
                entry.exit(1, errandId);
            }
        }
    }

    private static void installRule(String resourceName, int permitsPerSecond) {
        ParamFlowRule rule = new ParamFlowRule(resourceName)
                .setParamIdx(0)
                .setCount(permitsPerSecond)
                .setDurationInSec(1);
        List<ParamFlowRule> rules = new ArrayList<>(ParamFlowRuleManager.getRules());
        rules.removeIf(existing -> resourceName.equals(existing.getResource()));
        rules.add(rule);
        ParamFlowRuleManager.loadRules(rules);
    }
}
