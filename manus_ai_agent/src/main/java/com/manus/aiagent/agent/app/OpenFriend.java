package com.manus.aiagent.agent.app;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.manus.aiagent.advisor.MyLoggerAdvisor;
import com.manus.aiagent.chatmemory.VisualizedMemoryManager;
import com.manus.aiagent.rag.QueryRewriter;
import com.manus.aiagent.skill.SkillLoader;
import com.manus.aiagent.skill.SkillSessionManager;
import com.manus.aiagent.tools.terminal.TerminalCommandGate;
import com.manus.aiagent.tools.terminal.TerminalToolChatContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@Slf4j
public class OpenFriend {

    private final ChatModel chatModel;
    private final VisualizedMemoryManager memoryManager;
    private final ToolCallback[] allTools;
    private final ToolCallingManager toolCallingManager;
    private final SkillSessionManager skillSessionManager;
    private final SkillLoader skillLoader;
    private final TerminalCommandGate terminalCommandGate;

    private static final int MAX_LOOP_STEPS = 10;

    private static final String SYSTEM_PROMPT =
            "你是 OpenFriend，一个通用、可靠、真诚的智能伙伴。\n" +
                    "默认风格：简洁、直接、实用，避免空洞寒暄。\n" ;

    private static final String BASE_LOOP_SYSTEM_PROMPT = """
            你是 OpenFriend，一个通用、可靠、真诚的智能伙伴。
            你拥有各种工具可以使用，当需要使用工具时，请明确调用。
            如果不需要工具，直接回答用户问题。

            ## 可用工具
            webSearch、webScraping、fileOperation、resourceDownload、terminalOperation、pdfGeneration、memoryWorkspace、doTerminate

            ## Skill（技能）系统
            你还可以使用 `invokeSkill` 工具调用预定义的技能（Skill）。
            每个 Skill 包含专门的指令来处理特定类型的任务。
            当用户的请求符合某个 Skill 的触发条件时，优先使用对应的 Skill。
            
            Skill 相关工具：
            - `invokeSkill(skillName, arguments)` - 调用一个技能
            - `executeSkillScript(skillName, command)` - 执行技能中的脚本
            - `listSkills()` - 列出所有可用技能

            当你认为已经完成用户请求时，务必调用 doTerminate 工具。
            """;

    public OpenFriend(@Qualifier("dashScopeChatModel") ChatModel dashScopeChatModel,
                      VisualizedMemoryManager memoryManager,
                      ToolCallback[] allTools,
                      SkillSessionManager skillSessionManager,
                      SkillLoader skillLoader,
                      TerminalCommandGate terminalCommandGate) {
        this.chatModel = dashScopeChatModel;
        this.memoryManager = memoryManager;
        this.allTools = allTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.skillSessionManager = skillSessionManager;
        this.skillLoader = skillLoader;
        this.terminalCommandGate = terminalCommandGate;
    }

    /**
     * 构建包含 Skill 列表和已激活 Skills 的完整系统提示词
     */
    private String buildFullSystemPrompt(String memoryContext) {
        StringBuilder sb = new StringBuilder();
        sb.append(memoryContext).append("\n\n");
        sb.append(BASE_LOOP_SYSTEM_PROMPT).append("\n\n");
        
        // 注入已激活的 skills 内容（会话级持久化）
        String activeSkillsPrompt = skillSessionManager.buildActiveSkillsPrompt();
        if (!activeSkillsPrompt.isEmpty()) {
            sb.append(activeSkillsPrompt).append("\n\n");
        }
        
        // 注入可用的 skills 列表（供模型选择）
        String skillList = skillLoader.getSkillListDescription();
        if (skillList != null && !skillList.contains("没有可用")) {
            sb.append("---\n\n");
            sb.append(skillList);
        }
        
        return sb.toString();
    }

    public void reloadMemory(String chatId) {
        // Loop模式下通过重新获取记忆上下文来"刷新"
        log.info("记忆刷新请求，chatId: {}", chatId);
    }

    /**
     * 对话入口 - 统一使用Loop
     * 注意：不再在每次对话结束时清空 skill，skill 在整个会话中保持
     */
    public String doChat(String message, String chatId) {
        return runLoop(message, chatId);
        // 移除了 skillContextManager.clearContext()
        // skill 会话级持久化，不随单次对话结束而清空
    }

    /**
     * 执行ReAct循环（同步版本）
     */
    private String runLoop(String userMessage, String chatId) {
        TerminalToolChatContext.setChatId(chatId);
        try {
            Optional<String> confirmOutcome = terminalCommandGate.tryConsumeConfirmationReply(chatId, userMessage);
            if (confirmOutcome.isPresent()) {
                UserMessage userMsg = new UserMessage(userMessage);
                saveToMemory(chatId, userMsg, confirmOutcome.get());
                return confirmOutcome.get();
            }

            UserMessage userMsg = new UserMessage(userMessage);

            // 获取记忆上下文，并构建包含 skills 的完整提示词
            String memoryContext = memoryManager.exportAllContext();
            String fullSystemPrompt = buildFullSystemPrompt(memoryContext);

            // 构建不带advisor的ChatClient（不在这里注册工具，在 prompt 中注册）
            ChatClient chatClient = ChatClient.builder(chatModel)
                    .build();

            List<Message> messageList = new ArrayList<>();
            messageList.add(userMsg);

            String finalResult = null;

            for (int step = 0; step < MAX_LOOP_STEPS; step++) {
                log.info("ReAct循环 step {}/{}", step + 1, MAX_LOOP_STEPS);

                try {
                    Prompt prompt = new Prompt(messageList);
                    ChatResponse response = chatClient.prompt()
                            .system(fullSystemPrompt)
                            .messages(messageList)
                            .toolCallbacks(allTools)
                            .call()
                            .chatResponse();

                    List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();

                    if (toolCalls.isEmpty()) {
                        finalResult = response.getResult().getOutput().getText();
                        break;
                    }

                    messageList.add(response.getResult().getOutput());

                    // 打印LLM思考和工具调用
                    String thinkResult = response.getResult().getOutput().getText();
                    String toolCallInfo = toolCalls.stream()
                            .map(tc -> tc.name() + ": " + tc.arguments())
                            .collect(Collectors.joining("\n"));
                    log.info("=== Step {} 思考 ===\n{}\n=== 工具调用 ===\n{}", step + 1, thinkResult, toolCallInfo);

                    // 执行工具
                    ToolExecutionResult toolResult = toolCallingManager.executeToolCalls(prompt, response);

                    // 打印工具执行结果
                    ToolResponseMessage lastToolMsg = (ToolResponseMessage) toolResult.conversationHistory()
                            .get(toolResult.conversationHistory().size() - 1);
                    String toolResults = lastToolMsg.getResponses().stream()
                            .map(r -> "【" + r.name() + "】\n" + r.responseData())
                            .collect(Collectors.joining("\n\n"));
                    log.info("=== Step {} 工具执行结果 ===\n{}", step + 1, toolResults);

                    messageList.addAll(toolResult.conversationHistory());

                    // 检查是否终止
                    boolean terminated = lastToolMsg.getResponses().stream()
                            .anyMatch(r -> "doTerminate".equals(r.name()));

                    if (terminated) {
                        finalResult = response.getResult().getOutput().getText();
                        break;
                    }

                    log.info("=== Step {} 完成，继续下一步 ===", step + 1);

                } catch (Exception e) {
                    log.error("ReAct循环 step {} 失败", step + 1, e);
                    finalResult = "执行过程中出错: " + e.getMessage();
                    break;
                }
            }

            if (finalResult == null) {
                finalResult = "已达到最大步数限制";
            }

            // 保存到记忆（只有用户消息和最终回复）
            saveToMemory(chatId, userMsg, finalResult);

            return finalResult;
        } finally {
            TerminalToolChatContext.clear();
        }
    }

    /**
     * 执行ReAct循环（流式版本）
     * 注意：skill 会话级持久化，不随单次对话结束而清空
     */
    public Flux<String> doChatByStream(String message, String chatId, boolean enableThinking) {
        return runLoopStream(message, chatId, enableThinking);
        // 移除了 skillContextManager.clearContext()
    }

    /**
     * 这里是假的流式传输，因为要工具调用，无法事先确定这一轮对话是否需要工具调用，spring ai底层封装的模型，也没有支持 json 块传输，没发做到像claude code的那种真正的流式传输+流式工具调用
     * 就这样吧凑合一下:这个流式传输，最后返回的是只有一个String，就是finalResult，加上Flux的开销，甚至不如普通的
     * sprign ai好像可以实现，但是只能自己写提示词实现，这是官方文档：https://www.baeldung.com/spring-ai-chatclient-stream-response#respond
     *  -最大的问题是：它会按照我的json格式流式传输吗？要不是的话，会有重传机制吗？但是这是在一次传输过程中啊？怎么重传？难道说遇到一个块不是json格式的（大模型幻觉）我这个整个过程都作废了？之前流式传输的内容和之后还剩下的内容全都作废？
     *  -感觉应该把这种所有传输格式都固定下来才行，感觉应该在更底层做这个事情才对。然后所有的传输经过那个底层的chatBot，chatBot应该对应用层的上下文工程都是无感的，不然感觉很容易造成提示词混乱。而且就像上面说的，这种感觉应该提示词+代码规则来保证才好。
     * @param userMessage
     * @param chatId
     * @param enableThinking
     * @return
     */
    private Flux<String> runLoopStream(String userMessage, String chatId, boolean enableThinking) {
        return Flux.create(sink -> {
            TerminalToolChatContext.setChatId(chatId);
            try {
                Optional<String> confirmOutcome = terminalCommandGate.tryConsumeConfirmationReply(chatId, userMessage);
                if (confirmOutcome.isPresent()) {
                    UserMessage userMsg = new UserMessage(userMessage);
                    saveToMemory(chatId, userMsg, confirmOutcome.get());
                    sink.next(confirmOutcome.get());
                    sink.complete();
                    return;
                }

                UserMessage userMsg = new UserMessage(userMessage);

            // 获取记忆上下文，并构建包含 skills 的完整提示词
            String memoryContext = memoryManager.exportAllContext();
            String fullSystemPrompt = buildFullSystemPrompt(memoryContext);

            // 构建不带默认工具的ChatClient（工具在 prompt 中注册）
            ChatClient chatClient = ChatClient.builder(chatModel)
                    .build();

            List<Message> messageList = new ArrayList<>();
            messageList.add(userMsg);

            String finalResult = null;

            for (int step = 0; step < MAX_LOOP_STEPS; step++) {
                log.info("ReAct循环(流式) step {}/{}", step + 1, MAX_LOOP_STEPS);

                try {
                    Prompt prompt = new Prompt(messageList);
                    // 使用同步 call 等待完整响应（与 runLoop 逻辑一致）
                    ChatResponse response = chatClient.prompt()
                            .system(fullSystemPrompt)
                            .messages(messageList)
                            .toolCallbacks(allTools)
                            .options(DashScopeChatOptions.builder()
                                    .withEnableThinking(enableThinking)
                                    .withInternalToolExecutionEnabled(false)
                                    .build())
                            .call()
                            .chatResponse();

                    if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                        finalResult = "模型未返回有效响应";
                        break;
                    }

                    List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();

                    if (toolCalls.isEmpty()) {
                        finalResult = response.getResult().getOutput().getText();
                        break;
                    }

                    messageList.add(response.getResult().getOutput());

                    String thinkResult = response.getResult().getOutput().getText();
                    String toolCallInfo = toolCalls.stream()
                            .map(tc -> tc.name() + ": " + tc.arguments())
                            .collect(Collectors.joining("\n"));
                    log.info("=== Step {} 思考 ===\n{}\n=== 工具调用 ===\n{}", step + 1, thinkResult, toolCallInfo);

                    ToolExecutionResult toolResult = toolCallingManager.executeToolCalls(prompt, response);

                    ToolResponseMessage lastToolMsg = (ToolResponseMessage) toolResult.conversationHistory()
                            .get(toolResult.conversationHistory().size() - 1);
                    String toolResults = lastToolMsg.getResponses().stream()
                            .map(r -> "【" + r.name() + "】\n" + r.responseData())
                            .collect(Collectors.joining("\n\n"));
                    log.info("=== Step {} 工具执行结果 ===\n{}", step + 1, toolResults);

                    messageList.addAll(toolResult.conversationHistory());

                    boolean terminated = lastToolMsg.getResponses().stream()
                            .anyMatch(r -> "doTerminate".equals(r.name()));

                    if (terminated) {
                        finalResult = response.getResult().getOutput().getText();
                        break;
                    }

                } catch (Exception e) {
                    log.error("ReAct循环(流式) step {} 失败", step + 1, e);
                    finalResult = "执行过程中出错: " + e.getMessage();
                    break;
                }
            }

            if (finalResult == null) {
                finalResult = "已达到最大步数限制";
            }

            saveToMemory(chatId, userMsg, finalResult);

            // 只在最后推送结果
            sink.next(finalResult);
            sink.complete();
            } finally {
                TerminalToolChatContext.clear();
            }
        });
    }

    /**
     * 保存到记忆：只保存用户消息和最终回复
     */
    private void saveToMemory(String chatId, UserMessage userMsg, String assistantText) {
        // 直接使用ChatMemory保存
        // 这里简化处理，Loop模式下记忆由saveToMemory统一管理
        log.info("已保存到记忆: 用户消息 -> 最终回复 ({} 字符)", assistantText.length());
    }

    public record LoveReport(String title, List<String> suggestions) {
    }

    public LoveReport doChatWithReport(String message, String chatId) {
        // 这个功能需要用ChatClient
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultToolCallbacks(allTools)
                .build();

        String memoryContext = memoryManager.exportAllContext();
        String systemPrompt = memoryContext + "\n" + SYSTEM_PROMPT + "每次对话结束后，生成一份简洁的「对话摘要报告」，包含标题和建议列表。";

        LoveReport loveReport = chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .call()
                .entity(LoveReport.class);
        log.info("loveReport: {}", loveReport);
        return loveReport;
    }

    @Resource
    private Advisor loveAppRagCloudAdvisor;

    @Resource
    private QueryRewriter queryRewriter;

    public String doChatWithRag(String message, String chatId) {
        // 直接使用Loop
        return doChat(message, chatId);
    }

    public String doChatWithTools(String message, String chatId) {
        // 直接使用Loop
        return doChat(message, chatId);
    }

    @Resource
    private ToolCallbackProvider toolCallbackProvider;



    /**
     * 使用 MCP 工具进行对话（强调使用 MCP 工具，一轮对话完成）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithMcp(String message, String chatId) {
        // 获取记忆上下文
        String memoryContext = memoryManager.exportAllContext();

        // 构建强调 MCP 工具的系统提示词
        String mcpSystemPrompt = memoryContext + "\n\n" +
            "你拥有 MCP（Model Context Protocol）工具，包括高德地图和图片搜索功能。\n" +
            "当用户需要地理位置、路线规划或图片搜索时，优先使用 MCP 工具。\n" +
            "可用的 MCP 工具会出现在工具列表中。如果需要使用工具，请直接调用。\n" +
            "这是专门测试 MCP 工具的功能，请根据需要调用合适的 MCP 工具。";

        // 使用 ChatClient 调用
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultToolCallbacks(toolCallbackProvider) //设置默认的
                .build();

        return chatClient.prompt()
                .system(mcpSystemPrompt)
                .user(message)
                .toolCallbacks(toolCallbackProvider)//这里可以在每次对话中动态覆盖之前的工具调用
                .call()
                .content();
    }

}