package com.campusdash.bootstrap;

import com.campusdash.domain.auth.model.UserAccount;
import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.domain.credit.model.CreditEventType;
import com.campusdash.domain.credit.ports.CreditRankingPort;
import com.campusdash.domain.credit.ports.CreditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐"信用分快照 + 榜单成员"。
 *
 * 两个目的：
 *   1. 历史账号（早期注册 / SQL 导入的种子账号）可能没有 credit_score 行，补 60 分；
 *   2. Redis ZSET 只会在结算/校准后写入，重启或未结算的账号不在榜上，这里按 DB 事实全量 ZADD。
 *
 * 幂等：ensureScore 用 INSERT ... ON DUPLICATE KEY，ZADD 同分覆盖，重复启动不会改变分数。
 */
@Component
public class CreditScoreInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CreditScoreInitializer.class);

    private final UserRepository users;
    private final CreditRepository credits;
    private final CreditRankingPort ranking;

    public CreditScoreInitializer(UserRepository users, CreditRepository credits, CreditRankingPort ranking) {
        this.users = users;
        this.credits = credits;
        this.ranking = ranking;
    }

    @Override
    public void run(ApplicationArguments args) {
        int total = 0;
        for (UserAccount user : users.findAll()) {
            credits.ensureScore(user.userId(), CreditEventType.BASE_SCORE);
            ranking.update(user.campusId(), user.userId(), credits.scoreOf(user.userId()));
            total++;
        }
        log.info("信用分/榜单已同步 {} 个账号", total);
    }
}
