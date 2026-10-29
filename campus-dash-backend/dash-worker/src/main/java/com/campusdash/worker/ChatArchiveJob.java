package com.campusdash.worker;

import com.campusdash.domain.chat.ports.ChatArchivePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 每 4 小时把超过 24h 的 chat_message 归档到 history_message。 */
@Component
public class ChatArchiveJob {

    private static final Logger log = LoggerFactory.getLogger(ChatArchiveJob.class);
    private static final String LOCK_KEY = "chat:{archive}:lock";
    private static final Duration LOCK_TTL = Duration.ofMinutes(15);
    private static final int BATCH_SIZE = 500;
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final ChatArchivePort archivePort;
    private final StringRedisTemplate redis;
    private final String instanceId = UUID.randomUUID().toString();

    public ChatArchiveJob(ChatArchivePort archivePort, StringRedisTemplate redis) {
        this.archivePort = archivePort;
        this.redis = redis;
    }

    /** worker 重启时先补一次，避免停机期间超过 24h 的消息一直留在实时表。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        archive();
    }

    @Scheduled(cron = "0 0 */4 * * *")
    public void archive() {
        Boolean locked = redis.opsForValue().setIfAbsent(LOCK_KEY, instanceId, LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("聊天归档任务已被其他实例持有，跳过");
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(Duration.ofDays(1));
            int total = 0;
            int moved;
            do {
                moved = archivePort.archiveBefore(cutoff, BATCH_SIZE);
                total += moved;
            } while (moved > 0);
            if (total > 0) log.info("聊天归档完成：{} 条，cutoff={}", total, cutoff);
        } catch (RuntimeException e) {
            log.error("聊天归档任务失败", e);
        } finally {
            redis.execute(RELEASE, List.of(LOCK_KEY), instanceId);
        }
    }
}
