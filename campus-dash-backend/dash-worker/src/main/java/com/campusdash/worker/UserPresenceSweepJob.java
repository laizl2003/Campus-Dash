package com.campusdash.worker;

import com.campusdash.domain.auth.ports.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 在线状态兜底：用户直接关浏览器不会发登出请求，
 * 靠前端每 30 秒心跳刷新 last_seen_at；这里每分钟把超过 90 秒没心跳的账号置为离线。
 */
@Component
public class UserPresenceSweepJob {

    private static final Logger log = LoggerFactory.getLogger(UserPresenceSweepJob.class);

    private final UserRepository users;

    public UserPresenceSweepJob(UserRepository users) {
        this.users = users;
    }

    @Scheduled(fixedDelayString = "${dash.user.presence-sweep-ms:60000}")
    public void sweep() {
        int offline = users.sweepOffline(Instant.now().minusSeconds(90));
        if (offline > 0) {
            log.info("在线状态兜底：{} 个账号心跳超时置为离线", offline);
        }
    }
}
