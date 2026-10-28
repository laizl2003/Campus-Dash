package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.chat.model.ChatMessage;
import com.campusdash.domain.chat.ports.ChatMessageRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcChatMessageRepository implements ChatMessageRepository {

    private final JdbcTemplate jdbc;

    public JdbcChatMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final org.springframework.jdbc.core.RowMapper<ChatMessage> MAPPER =
            (ResultSet rs, int n) -> new ChatMessage(
                    rs.getLong("id"),
                    rs.getString("room_id"),
                    rs.getLong("campus_id"),
                    rs.getString("sender_id"),
                    rs.getString("sender_name"),
                    rs.getString("sender_role"),
                    rs.getString("content"),
                    rs.getString("msg_type"),
                    rs.getString("client_msg_id"),
                    rs.getInt("status"),
                    rs.getTimestamp("created_at").toInstant());

    private static final String COLUMNS =
            "id, room_id, campus_id, sender_id, sender_name, sender_role, content, msg_type, client_msg_id, status, created_at";

    @Override
    public void save(ChatMessage m) {
        jdbc.update("""
                INSERT INTO chat_message (id, room_id, campus_id, sender_id, sender_name, sender_role,
                                          content, msg_type, client_msg_id, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, m.id(), m.roomId(), m.campusId(), m.senderId(), m.senderName(), m.senderRole(),
                m.content(), m.msgType(), m.clientMsgId(), m.status(),
                Timestamp.from(m.createdAt()));
    }

    @Override
    public Optional<ChatMessage> findBySenderAndClientId(String senderId, String clientMsgId) {
        List<ChatMessage> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM chat_message WHERE sender_id = ? AND client_msg_id = ? LIMIT 1",
                MAPPER, senderId, clientMsgId);
        return rows.stream().findFirst();
    }

    @Override
    public List<ChatMessage> latest(String roomId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM chat_message WHERE room_id = ? AND status = 1 ORDER BY id DESC LIMIT ?",
                MAPPER, roomId, limit);
    }

    @Override
    public List<ChatMessage> before(String roomId, long beforeId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM chat_message WHERE room_id = ? AND status = 1 AND id < ? ORDER BY id DESC LIMIT ?",
                MAPPER, roomId, beforeId, limit);
    }

    @Override
    public List<ChatMessage> after(String roomId, long afterId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM chat_message WHERE room_id = ? AND status = 1 AND id > ? ORDER BY id ASC LIMIT ?",
                MAPPER, roomId, afterId, limit);
    }
}
