package com.manus.aiagent.agent.app;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.advisors.SkillPromptAugmentAdvisor;
import com.manus.aiagent.advisor.MyLoggerAdvisor;
import com.manus.aiagent.chatmemory.ChatMessageStore;
import com.manus.aiagent.chatmemory.VisualizedMemoryManager;
import com.manus.aiagent.advisor.VisualizedMemoryAdvisor;
import com.manus.aiagent.rag.QueryRewriter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

@Component
@Slf4j
public class OpenFriend {
//    private final ReactAgent reactAgent;

    private final ChatClient chatClient;
    private final VisualizedMemoryAdvisor visualizedMemoryAdvisor;//这个东西需要做成bean吗？因为我想要做的是多个agent共享一套记忆
    /** 与 Spring 容器 {@code allTools} Bean 相同引用，已通过 {@link ChatClient.Builder#defaultToolCallbacks} 注册到本 Agent。 */
    private final ToolCallback[] allTools;

    // 默认系统提示（当 SOUL.md 缺失时作为兜底）
    private static final String SYSTEM_PROMPT =
            "你是 OpenFriend，一个通用、可靠、真诚的智能伙伴。\n" +
                    "默认风格：简洁、直接、实用，避免空洞寒暄。\n" +
                    "当用户提出角色扮演需求时，遵循 memory.md 中的 role 节点执行；\n" +
                    "若用户未提出明确角色要求，则保持 OpenFriend 默认角色。";

    public OpenFriend(@Qualifier("dashScopeChatModel") ChatModel dashScopeChatModel,
                   VisualizedMemoryManager memoryManager,
                   @Qualifier("diaryVectorStore") VectorStore diaryVectorStore,
                   @Qualifier("knowledgeVectorStore") VectorStore knowledgeVectorStore,
                   ChatMessageStore chatMessageStore,
                   ChatMemory shortTermMemory,
                   SkillPromptAugmentAdvisor skillPromptAugmentAdvisor,
                   ToolCallback[] allTools) {

        this.allTools = allTools;

        this.visualizedMemoryAdvisor = new VisualizedMemoryAdvisor(
                memoryManager,
                shortTermMemory,
                dashScopeChatModel,
                diaryVectorStore,
                knowledgeVectorStore,
                chatMessageStore
        );

        this.chatClient = ChatClient.builder(dashScopeChatModel)
                .defaultAdvisors(
                        visualizedMemoryAdvisor,
                        skillPromptAugmentAdvisor,
                        new MyLoggerAdvisor()
                )
                .defaultToolCallbacks(allTools)
                .build();
    }

    /**
     * 重新加载记忆：清除旧对话上下文 + 从磁盘重读 SOUL.md、memory.md、日记
     */
    public void reloadMemory(String chatId) {
        visualizedMemoryAdvisor.reload(chatId);
    }

    /**
     * AI 基础对话（支持多轮对话记忆）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChat(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    /**
     * AI 基础对话（支持多轮对话记忆，SSE 流式传输）
     *
     * @param message
     * @param chatId
     * @param enableThinking 是否开启深度思考
     * @return
     */
    public Flux<String> doChatByStream(String message, String chatId, boolean enableThinking) {
        return chatClient
                .prompt()
                .user(message)
                .options(DashScopeChatOptions.builder()
                        .withEnableThinking(enableThinking)
                        .build())
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }

    public record LoveReport(String title, List<String> suggestions) {

    }

    /**
     * AI 对话报告功能（结构化输出）
     *
     * @param message
     * @param chatId
     * @return
     */
    public LoveReport doChatWithReport(String message, String chatId) {
        LoveReport loveReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话结束后，生成一份简洁的「对话摘要报告」，包含标题和建议列表。")
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .entity(LoveReport.class);
        log.info("loveReport: {}", loveReport);
        return loveReport;
    }



    @Resource
    private Advisor loveAppRagCloudAdvisor;

//    @Resource(name = "loveAppVectorStore")
//    private VectorStore loveAppVectorStore;

//    @Resource(name = "pgVectorVectorStore")
//    private VectorStore pgVectorVectorStore;

    @Resource
    private QueryRewriter queryRewriter;

    /**
     * 和 RAG 知识库进行对话
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithRag(String message, String chatId) {
        // 查询重写
        String rewrittenMessage = queryRewriter.doQueryRewrite(message);
        ChatResponse chatResponse = chatClient
                .prompt()
                // 使用改写后的查询
                .user(rewrittenMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                // 应用 RAG 知识库问答
//                .advisors(QuestionAnswerAdvisor.builder(loveAppVectorStore).build())
                // 应用 RAG 检索增强服务（基于云知识库服务）
//                .advisors(loveAppRagCloudAdvisor)
                // 应用 RAG 检索增强服务（基于 PgVector 向量存储）
//                .advisors(new QuestionAnswerAdvisor(pgVectorVectorStore))
                // 应用自定义的 RAG 检索增强服务（文档查询器 + 上下文增强器）
//                .advisors(
//                        LoveAppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor(
//                                loveAppVectorStore, "单身"
//                        )
//                )
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    /**
     * AI 对话功能（支持调用工具）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithTools(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(new MyLoggerAdvisor())
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    /**
     * AI 对话功能（支持 Skills 安装与使用 + 工具调用）
     * 用户可以通过自然语言指令安装/卸载/列出 Skills
     */
    public Flux<String> doChatWithSkills(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(new MyLoggerAdvisor())
                .stream()
                .content();
    }

    // AI 调用 MCP 服务

    @Resource
    private ToolCallbackProvider toolCallbackProvider;

    /**
     * AI 对话功能（调用 MCP 服务）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithMcp(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }



    /**
     * 新增私有方法：加载 SOUL.md 身份文件
     */
    private String loadSoulPrompt() {
        File soulFile = new File(System.getProperty("user.dir") + "/workspace/memory/SOUL.md");
        try {
            if (soulFile.exists()) {
                log.info("从 workspace/memory/SOUL.md 加载 Agent 人设成功");
                return Files.readString(soulFile.toPath());
            } else {
                // 如果没有找到文件，自动建目录并生成模板
                soulFile.getParentFile().mkdirs();
                Files.writeString(soulFile.toPath(), SYSTEM_PROMPT);
                log.info("未找到 SOUL.md，已自动生成默认模板文件");
                return SYSTEM_PROMPT;
            }
        } catch (Exception e) {
            log.error("加载 SOUL.md 失败，使用系统默认人设", e);
            return SYSTEM_PROMPT;
        }
    }
}
