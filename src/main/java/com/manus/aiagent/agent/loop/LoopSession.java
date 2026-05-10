package com.manus.aiagent.agent.loop;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Loop会话管理类，负责管理单个loop内的消息缓冲区和压缩
 */
public class LoopSession {
    private final String sessionId;
    private final String chatId;
    private final List<Message> internalMessages = new ArrayList<>();
    private Message firstUserMessage;
    private Message finalResult;
    private boolean active = true;
    private final LocalDateTime startTime;

    public LoopSession(String sessionId, String chatId) {
        this.sessionId = sessionId;
        this.chatId = chatId;
        this.startTime = LocalDateTime.now();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getChatId() {
        return chatId;
    }

    public boolean isActive() {
        return active;
    }

    public void setFirstUserMessage(Message message) {
        this.firstUserMessage = message;
    }

    public void setFinalResult(Message message) {
        this.finalResult = message;
    }

    public void addInternalMessage(Message message) {
        if (active) {
            internalMessages.add(message);
        }
    }

    public void addInternalMessages(List<Message> messages) {
        if (active) {
            internalMessages.addAll(messages);
        }
    }

    public void endLoop() {
        this.active = false;
    }

    /**
     * 压缩loop内的中间消息，生成摘要
     */
    public String compressToSummary(ChatClient chatClient) {
        if (internalMessages.isEmpty()) {
            return "无中间消息";
        }

        // 如果消息较少，使用简单规则压缩
        if (internalMessages.size() <= 3) {
            return compressSimple();
        }

        // 消息较多时，使用LLM生成摘要
        return compressWithLLM(chatClient);
    }

    private String compressSimple() {
        StringBuilder summary = new StringBuilder();
        summary.append("Loop摘要（").append(internalMessages.size()).append("条消息）:\n");

        int toolCallCount = 0;
        int thoughtCount = 0;
        int resultCount = 0;

        for (Message msg : internalMessages) {
            String content = extractContent(msg);
            if (content.contains("工具调用") || content.contains("Tool")) {
                toolCallCount++;
            } else if (content.contains("思考") || content.contains("Thought")) {
                thoughtCount++;
            } else if (content.contains("结果") || content.contains("Result")) {
                resultCount++;
            }
        }

        if (toolCallCount > 0) {
            summary.append("- 执行了").append(toolCallCount).append("次工具调用\n");
        }
        if (thoughtCount > 0) {
            summary.append("- 进行了").append(thoughtCount).append("次思考\n");
        }
        if (resultCount > 0) {
            summary.append("- 产生了").append(resultCount).append("个结果\n");
        }

        return summary.toString();
    }

    private String compressWithLLM(ChatClient chatClient) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("请将以下ReAct循环中的中间消息压缩为简洁摘要。\n");
            prompt.append("保留：关键决策、重要工具调用结果、最终结论\n");
            prompt.append("去除：重复思考、中间状态、冗余信息\n\n");
            prompt.append("消息列表：\n");

            for (int i = 0; i < internalMessages.size(); i++) {
                Message msg = internalMessages.get(i);
                String role = getMessageRole(msg);
                String content = extractContent(msg);
                prompt.append(i + 1).append(". [").append(role).append("] ")
                      .append(truncateContent(content, 200)).append("\n");
            }

            ChatResponse response = chatClient.prompt()
                    .user(prompt.toString())
                    .call()
                    .chatResponse();

            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            // 如果LLM压缩失败，回退到简单压缩
            return compressSimple();
        }
    }

    private String getMessageRole(Message message) {
        if (message instanceof UserMessage) {
            return "用户";
        } else if (message instanceof AssistantMessage) {
            return "助手";
        } else {
            return message.getMessageType().getValue();
        }
    }

    private String extractContent(Message message) {
        if (message instanceof UserMessage) {
            return ((UserMessage) message).getText();
        } else if (message instanceof AssistantMessage) {
            return ((AssistantMessage) message).getText();
        } else {
            // 对于其他类型的Message，返回空字符串
            return "";
        }
    }

    private String truncateContent(String content, int maxLength) {
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }

    public List<Message> getInternalMessages() {
        return new ArrayList<>(internalMessages);
    }

    public Message getFirstUserMessage() {
        return firstUserMessage;
    }

    public Message getFinalResult() {
        return finalResult;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public int getMessageCount() {
        return internalMessages.size();
    }
}