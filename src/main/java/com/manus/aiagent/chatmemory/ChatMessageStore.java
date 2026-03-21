package com.manus.aiagent.chatmemory;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 基于 JdbcTemplate 的聊天消息持久化，用于前端展示历史消息
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatMessageStore {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id BIGSERIAL PRIMARY KEY,
                    conversation_id VARCHAR(128) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    content TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_chat_msg_conv_id
                ON chat_messages (conversation_id, created_at)
                """);
        log.info("chat_messages 表已就绪");
    }

    public void saveMessage(String conversationId, String role, String content) {
        jdbcTemplate.update(
                "INSERT INTO chat_messages (conversation_id, role, content, created_at) VALUES (?, ?, ?, ?)",
                conversationId, role, content, Timestamp.valueOf(LocalDateTime.now())
        );
    }

    public List<ChatMessageDTO> getMessages(String conversationId) {
        return jdbcTemplate.query(
                "SELECT role, content, created_at FROM chat_messages WHERE conversation_id = ? ORDER BY created_at ASC",
                (rs, rowNum) -> {
                    ChatMessageDTO dto = new ChatMessageDTO();
                    dto.setRole(rs.getString("role"));
                    dto.setContent(rs.getString("content"));
                    dto.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                    return dto;
                },
                conversationId
        );
    }

    @Data
    public static class ChatMessageDTO {
        private String role;
        private String content;
        private LocalDateTime createdAt;
    }
}
