package com.campusdash.infrastructure.cache;

import com.campusdash.domain.chat.model.ChatMessage;
import com.campusdash.domain.chat.ports.ChatHistoryCachePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Redis ZSET 保存最近 200 条聊天消息；DB 仍是事实源。 */
@Component
public class RedisChatHistoryCache implements ChatHistoryCachePort {

    private static final Logger log = LoggerFactory.getLogger(RedisChatHistoryCache.class);
    private static final int MAX_SIZE = 2000;
    private static final String PREFIX = "chat:recent:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisChatHistoryCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void push(ChatMessage message) {
        String key = PREFIX + message.roomId();
        try {
            redis.opsForZSet().add(key, objectMapper.writeValueAsString(toMap(message)),
                    message.id());
            // ZSET 按 id 升序，删除排名 0 到 -(MAX_SIZE+1)，保留最后 2000 条
            redis.opsForZSet().removeRange(key, 0, -(MAX_SIZE + 1));
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.warn("聊天历史缓存写入失败 messageId={}", message.id(), e);
        }
    }

    @Override
    public List<ChatMessage> latest(String roomId, int limit) {
        if (limit <= 0) return List.of();
        try {
            Set<String> rows = redis.opsForZSet().reverseRange(PREFIX + roomId, 0, limit - 1);
            if (rows == null || rows.isEmpty()) return List.of();
            List<ChatMessage> messages = new ArrayList<>(rows.size());
            for (String json : rows) {
                messages.add(fromJson(json));
            }
            java.util.Collections.reverse(messages);
            return messages;
        } catch (Exception e) {
            log.debug("聊天历史缓存读取失败 room={}", roomId);
            return List.of();
        }
    }

    @Override
    public int size(String roomId) {
        Long size = redis.opsForZSet().zCard(PREFIX + roomId);
        return size == null ? 0 : size.intValue();
    }

    @Override
    public void invalidate(String roomId) {
        redis.delete(PREFIX + roomId);
    }

    private static Map<String, Object> toMap(ChatMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.id());
        map.put("roomId", m.roomId());
        map.put("campusId", m.campusId());
        map.put("senderId", m.senderId());
        map.put("senderName", m.senderName());
        map.put("senderRole", m.senderRole());
        map.put("content", m.content());
        map.put("msgType", m.msgType());
        map.put("errandId", m.errandId());
        map.put("errandStatus", m.errandStatus());
        map.put("clientMsgId", m.clientMsgId());
        map.put("status", m.status());
        map.put("createdAt", m.createdAt().toString());
        return map;
    }

    private ChatMessage fromJson(String json) throws Exception {
        Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
        return new ChatMessage(
                ((Number) map.get("id")).longValue(),
                (String) map.get("roomId"),
                ((Number) map.get("campusId")).longValue(),
                (String) map.get("senderId"),
                (String) map.get("senderName"),
                (String) map.get("senderRole"),
                (String) map.get("content"),
                (String) map.get("msgType"),
                map.get("errandId") == null ? null : ((Number) map.get("errandId")).longValue(),
                (String) map.get("errandStatus"),
                (String) map.get("clientMsgId"),
                ((Number) map.get("status")).intValue(),
                Instant.parse((String) map.get("createdAt")));
    }
}
