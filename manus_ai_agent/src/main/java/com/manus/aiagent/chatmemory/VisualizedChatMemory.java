package com.manus.aiagent.chatmemory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯文本可视化对话记忆（支持真实物理换行，极致人类友好）
 */
public class VisualizedChatMemory implements ChatMemory {

    private final String BASE_DIR;
    private final int maxWindowSize;

    // 视觉分隔符，既能让用户看着舒服，又能作为代码反向读取时的“定界符”
    private static final String SEPARATOR = "--------------------------------------------------";

    public VisualizedChatMemory(String dir, int maxWindowSize) {
        this.BASE_DIR = dir;
        this.maxWindowSize = maxWindowSize;
        new File(dir).mkdirs();
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        // 后缀改为 .txt，代表这是纯文本阅读文件
        File file = new File(BASE_DIR, conversationId + ".txt");
        try {
            StringBuilder sb = new StringBuilder();
            for (Message msg : messages) {
                // 1. 写入角色头部
                sb.append("[ROLE: ").append(msg.getMessageType().getValue().toUpperCase()).append("]\n");

                // 2. 写入真实内容（用 trim() 去掉首尾多余的空行，保证排版紧凑）
                sb.append(msg.getText().trim()).append("\n");

                // 3. 写入底部边界
                sb.append(SEPARATOR).append("\n");
            }
            // 追加写入
            Files.writeString(file.toPath(), sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        File file = new File(BASE_DIR, conversationId + ".txt");
        List<Message> history = new ArrayList<>();
        if (!file.exists()) return history;

        try {
            List<String> lines = Files.readAllLines(file.toPath());
            String currentRole = null;
            StringBuilder currentContent = new StringBuilder();

            // 逐行解析文本块
            for (String line : lines) {
                if (line.startsWith("[ROLE: ") && line.endsWith("]")) {
                    // 匹配到角色头，开始记录新消息
                    currentRole = line.substring(7, line.length() - 1);
                    currentContent.setLength(0); // 清空内容缓冲
                } else if (line.equals(SEPARATOR)) {
                    // 匹配到分隔符，说明当前消息结束，打包存入列表
                    if (currentRole != null) {
                        String content = currentContent.toString().trim();
                        if ("USER".equals(currentRole)) {
                            history.add(new UserMessage(content));
                        } else if ("ASSISTANT".equals(currentRole)) {
                            history.add(new AssistantMessage(content));
                        } else if ("SYSTEM".equals(currentRole)) {
                            history.add(new SystemMessage(content));
                        }
                        currentRole = null; // 重置
                    }
                } else {
                    // 属于中间的内容部分，保留真实的换行符拼接到一起
                    if (currentRole != null) {
                        currentContent.append(line).append("\n");
                    }
                }
            }

            // 防爆盾：截取最近 N 条
            if (history.size() > maxWindowSize) {
                return history.subList(history.size() - maxWindowSize, history.size());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return history;
    }

    @Override
    public void clear(String conversationId) {
        File file = new File(BASE_DIR, conversationId + ".txt");
        if (file.exists()) {
            file.delete();
        }
    }
}