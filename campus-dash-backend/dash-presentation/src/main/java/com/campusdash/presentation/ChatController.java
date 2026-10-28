package com.campusdash.presentation;

import com.campusdash.application.usecase.chat.QueryChatHistoryUseCase;
import com.campusdash.application.usecase.chat.SendChatMessageUseCase;
import com.campusdash.domain.chat.model.ChatMessage;
import com.campusdash.presentation.realtime.WsSessionRegistry;
import com.campusdash.presentation.auth.CurrentUser;
import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 广场聊天：历史查询与 HTTP 发送（WS 发送走同一个用例）。 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final QueryChatHistoryUseCase history;
    private final SendChatMessageUseCase sender;
    private final WsSessionRegistry wsSessions;

    public ChatController(QueryChatHistoryUseCase history, SendChatMessageUseCase sender,
                          WsSessionRegistry wsSessions) {
        this.history = history;
        this.sender = sender;
        this.wsSessions = wsSessions;
    }

    /** 在线人数直接来自当前 WebSocket 注册表，登出 closeUser 后立即减少。 */
    @GetMapping("/online")
    public Result<Map<String, Object>> online() {
        return Result.ok(Map.of("count", wsSessions.onlineUsers()));
    }

    @GetMapping("/messages")
    public Result<Map<String, Object>> messages(@RequestParam(defaultValue = "50") int limit,
                                                @RequestParam(required = false) Long beforeId,
                                                @RequestParam(required = false) Long afterId) {
        List<Map<String, Object>> items = history
                .history(SendChatMessageUseCase.ROOM, limit, beforeId, afterId)
                .stream().map(ChatController::view).toList();
        return Result.ok(Map.of("items", items));
    }

    @PostMapping("/messages")
    public Result<Map<String, Object>> send(@RequestBody SendRequest request) {
        ChatMessage message = sender.send(CurrentUser.get(), request.clientMsgId(), request.content());
        return Result.ok(view(message));
    }

    record SendRequest(String clientMsgId, String content) {
    }

    private static Map<String, Object> view(ChatMessage m) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", String.valueOf(m.id()));
        item.put("roomId", m.roomId());
        item.put("senderId", m.senderId());
        item.put("senderName", m.senderName());
        item.put("senderRole", m.senderRole());
        item.put("content", m.content());
        item.put("createdAt", m.createdAt().toString());
        item.put("clientMsgId", m.clientMsgId());
        return item;
    }
}
