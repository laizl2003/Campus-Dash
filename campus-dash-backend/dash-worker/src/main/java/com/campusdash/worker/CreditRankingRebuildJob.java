package com.campusdash.worker;

import com.campusdash.domain.auth.model.UserAccount;
import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.domain.credit.ports.CreditRankingPort;
import com.campusdash.domain.credit.ports.CreditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 排行榜缓存重建 job（默认 60 秒一次）。
 *
 * 为什么需要它：ZSET 是"展示缓存"，Redis 被清空、实例重启、写入失败都可能让它缺人；
 * 这里按 MySQL 的 credit_score 全量重放，保证榜单最终与事实一致。
 * 只做 ZADD，不重算分数——分数校准是 CreditCalibrationJob 的职责。
 */
@Component
public class CreditRankingRebuildJob {

    private static final Logger log = LoggerFactory.getLogger(CreditRankingRebuildJob.class);

    private final UserRepository users;
    private final CreditRepository credits;
    private final CreditRankingPort ranking;

    public CreditRankingRebuildJob(UserRepository users, CreditRepository credits, CreditRankingPort ranking) {
        this.users = users;
        this.credits = credits;
        this.ranking = ranking;
    }

    @Scheduled(fixedDelayString = "${dash.credit.ranking-rebuild-ms:60000}",
               initialDelayString = "${dash.credit.ranking-rebuild-initial-ms:15000}")
    public void rebuild() {
        int total = 0;
        for (UserAccount user : users.findAll()) {
            ranking.update(user.campusId(), user.userId(), credits.scoreOf(user.userId()));
            total++;
        }
        if (total > 0) {
            log.info("排行榜缓存已重建 {} 个账号", total);
        }
    }
}
