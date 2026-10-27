package com.campusdash.presentation;

import com.campusdash.application.usecase.query.QueryNotificationUseCase;
import com.campusdash.presentation.auth.CurrentUser;
import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final QueryNotificationUseCase queryUseCase;

    public NotificationController(QueryNotificationUseCase queryUseCase) {
        this.queryUseCase = queryUseCase;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        String userId = CurrentUser.get();
        return Result.ok(queryUseCase.list(userId, page, size).stream()
                .map(v -> Map.<String, Object>of(
                        "id", v.id(), "errandId", v.errandId(), "type", v.type(),
                        "content", v.content(), "time", v.time().toString(), "read", v.read()))
                .toList());
    }

    /** 点开某条消息时标记已读（前端乐观更新，失败也不阻塞跳转） */
    @PostMapping("/{id}/read")
    public Result<Map<String, Object>> markRead(@PathVariable long id) {
        int changed = queryUseCase.markRead(CurrentUser.get(), id);
        return Result.ok(Map.of("changed", changed));
    }

    @GetMapping("/unread")
    public Result<Map<String, Object>> unread() {
        return Result.ok(Map.of("count", queryUseCase.unread(CurrentUser.get())));
    }
}
