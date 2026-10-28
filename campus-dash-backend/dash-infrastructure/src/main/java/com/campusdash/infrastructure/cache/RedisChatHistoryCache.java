package com.campusdash.infrastructure.cache;

import com.campusdash.domain.chat.model.ChatMessage;
import com.campusdash.domain.chat.ports.ChatHistoryCachePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 最近聊天缓存：
 *   ZSET 只存 messageId（排序）
 *   HASH 存 id -> 消息 JSON
 *   HASH 存 errandId -> messageId（任务卡片状态更新索引）
 *   HASH 存 messageId -> errandId（淘汰时清理反向索引）
 *
 * 写入/淘汰用 Lua 保证 ZSET 与两个 HASH 一致；读取用 HMGET。
 */
@Component
public class RedisChatHistoryCache implements ChatHistoryCachePort {

    private static final Logger log = LoggerFactory.getLogger(RedisChatHistoryCache.class);
    private static final int MAX_SIZE = 2000;
    private static final Duration TTL = Duration.ofHours(24);

    private static final DefaultRedisScript<Long> ADD = new DefaultRedisScript<>("""
            local id = ARGV[1]
            local json = ARGV[2]
            local errandId = ARGV[3]
            local maxSize = tonumber(ARGV[4])
            local ttl = tonumber(ARGV[5])

            redis.call('HSET', KEYS[2], id, json)
            redis.call('ZADD', KEYS[1], id, id)

            if errandId ~= '' then
              redis.call('HSET', KEYS[3], errandId, id)
              redis.call('HSET', KEYS[4], id, errandId)
            end

            local n = redis.call('ZCARD', KEYS[1])
            if n > maxSize then
              local over = n - maxSize
              local evicted = redis.call('ZRANGE', KEYS[1], 0, over - 1)
              redis.call('ZREMRANGEBYRANK', KEYS[1], 0, over - 1)
              for _, oldId in ipairs(evicted) do
                redis.call('HDEL', KEYS[2], oldId)
                local oldErrand = redis.call('HGET', KEYS[4], oldId)
                if oldErrand then
                  local current = redis.call('HGET', KEYS[3], oldErrand)
                  if current == oldId then
                    redis.call('HDEL', KEYS[3], oldErrand)
                  end
                  redis.call('HDEL', KEYS[4], oldId)
                end
              end
            end

            redis.call('EXPIRE', KEYS[1], ttl)
            redis.call('EXPIRE', KEYS[2], ttl)
            redis.call('EXPIRE', KEYS[3], ttl)
            redis.call('EXPIRE', KEYS[4], ttl)
            return n
            """, Long.class);

    private static final DefaultRedisScript<Long> UPDATE_ERRAND = new DefaultRedisScript<>("""
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if not current or current ~= ARGV[2] then return 0 end
            if redis.call('HEXISTS', KEYS[2], ARGV[2]) == 0 then return 0 end
            redis.call('HSET', KEYS[2], ARGV[2], ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisChatHistoryCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void push(ChatMessage message) {
        try {
            String json = objectMapper.writeValueAsString(toMap(message));
            redis.execute(ADD,
                    List.of(recent(message.roomId()), payload(message.roomId()),
                            errandIndex(message.roomId()), messageErrand(message.roomId())),
                    String.valueOf(message.id()), json,
                    message.errandId() == null ? "" : String.valueOf(message.errandId()),
                    String.valueOf(MAX_SIZE), String.valueOf(TTL.toSeconds()));
        } catch (Exception e) {
            log.warn("聊天历史缓存写入失败 messageId={}", message.id(), e);
        }
    }

    @Override
    public List<ChatMessage> latest(String roomId, int limit) {
        if (limit <= 0) return List.of();
        try {
            Set<String> ids = redis.opsForZSet().reverseRange(recent(roomId), 0, limit - 1);
            if (ids == null || ids.isEmpty()) return List.of();
            List<String> idList = new ArrayList<>(ids);
            List<String> payloads = redis.<String, String>opsForHash()
                    .multiGet(payload(roomId), idList);
            List<ChatMessage> messages = new ArrayList<>(idList.size());
            for (int i = 0; i < idList.size(); i++) {
                String json = payloads.get(i);
                if (json == null) continue;
                messages.add(fromJson(json));
            }
            Collections.reverse(messages);
            return messages;
        } catch (Exception e) {
            log.debug("聊天历史缓存读取失败 room={}", roomId);
            return List.of();
        }
    }

    @Override
    public int size(String roomId) {
        Long size = redis.opsForZSet().zCard(recent(roomId));
        return size == null ? 0 : size.intValue();
    }

    @Override
    public void updateErrandStatus(String roomId, long errandId, String status) {
        try {
            String indexKey = errandIndex(roomId);
            String messageId = (String) redis.opsForHash().get(indexKey, String.valueOf(errandId));
            if (messageId == null) return;

            String payloadKey = payload(roomId);
            Object raw = redis.opsForHash().get(payloadKey, messageId);
            if (raw == null) return;

            Map<String, Object> map = objectMapper.readValue(raw.toString(), new TypeReference<>() {});
            map.put("errandStatus", status);
            String json = objectMapper.writeValueAsString(map);
            redis.execute(UPDATE_ERRAND, List.of(indexKey, payloadKey),
                    String.valueOf(errandId), messageId, json);
        } catch (Exception e) {
            log.warn("ERRAND 卡片状态缓存更新失败 errandId={} status={}", errandId, status, e);
        }
    }

    @Override
    public void invalidate(String roomId) {
        redis.delete(List.of(recent(roomId), payload(roomId),
                errandIndex(roomId), messageErrand(roomId)));
    }

    private static String recent(String roomId) { return "chat:recent:" + roomId; }
    private static String payload(String roomId) { return "chat:payload:" + roomId; }
    private static String errandIndex(String roomId) { return "chat:errand:" + roomId; }
    private static String messageErrand(String roomId) { return "chat:message-errand:" + roomId; }

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
