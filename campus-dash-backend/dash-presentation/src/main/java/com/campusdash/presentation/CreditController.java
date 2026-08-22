package com.campusdash.presentation;

import com.campusdash.application.usecase.query.QueryCreditUseCase;
import com.campusdash.presentation.auth.CurrentUser;
import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 信用分接口：我的分数/事件流水、校区排行榜。
 *
 * 分数展示带事件说明——让用户知道"为什么是这个分"，
 * 信用体系的可申诉性从透明开始。
 */
@RestController
@RequestMapping("/api/credit")
public class CreditController {

    private final QueryCreditUseCase queryUseCase;

    public CreditController(QueryCreditUseCase queryUseCase) {
        this.queryUseCase = queryUseCase;
    }

    @GetMapping("/self")
    public Result<Map<String, Object>> self() {
        long userId = CurrentUser.get();
        List<Map<String, Object>> events = queryUseCase.myEvents(userId).stream()
                .map(e -> Map.<String, Object>of(
                        "type", e.type().name(),
                        "description", e.type().description(),
                        "delta", e.delta(),
                        "refId", String.valueOf(e.refId()),
                        "time", e.createdAt().toString()))
                .toList();
        return Result.ok(Map.of(
                "score", queryUseCase.myScore(userId),
                "windowDays", com.campusdash.domain.credit.model.CreditEventType.WINDOW_DAYS,
                "events", events));
    }

    @GetMapping("/ranking")
    public Result<List<Map<String, Object>>> ranking(@RequestParam(defaultValue = "1") long campusId,
                                                     @RequestParam(defaultValue = "20") int limit) {
        CurrentUser.get();
        return Result.ok(queryUseCase.ranking(campusId, limit).stream()
                .map(e -> Map.<String, Object>of(
                        "userId", String.valueOf(e.userId()),
                        "score", e.score()))
                .toList());
    }
}
