package com.campusdash.application.usecase.query;

import com.campusdash.domain.credit.model.CreditEvent;
import com.campusdash.domain.credit.model.CreditEventType;
import com.campusdash.domain.credit.ports.CreditRankingPort;
import com.campusdash.domain.credit.ports.CreditRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** 信用分查询：我的分数与事件流水、校区排行榜 */
@Service
public class QueryCreditUseCase {

    private final CreditRepository creditRepository;
    private final CreditRankingPort rankingPort;

    public QueryCreditUseCase(CreditRepository creditRepository, CreditRankingPort rankingPort) {
        this.creditRepository = creditRepository;
        this.rankingPort = rankingPort;
    }

    public int myScore(long userId) {
        return creditRepository.scoreOf(userId);
    }

    public List<CreditEvent> myEvents(long userId) {
        return creditRepository.recentEvents(userId, CreditEventType.WINDOW_DAYS, 50);
    }

    public List<CreditRankingPort.Entry> ranking(long campusId, int limit) {
        return rankingPort.top(campusId, Math.min(Math.max(limit, 1), 100));
    }
}
