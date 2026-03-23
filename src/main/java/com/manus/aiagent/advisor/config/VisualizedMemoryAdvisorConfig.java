package com.manus.aiagent.advisor.config;

import com.manus.aiagent.advisor.VisualizedMemoryAdvisor;
import com.manus.aiagent.chatmemory.ChatMessageStore;
import com.manus.aiagent.chatmemory.VisualizedMemoryManager;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 将 {@link VisualizedMemoryAdvisor} 注册为单例 Bean，供 {@link com.manus.aiagent.agent.app.OpenFriend}、
 * {@link com.manus.aiagent.agent.manus.ManusAgent} 等共用同一套磁盘记忆加载、短期记忆与坍缩逻辑。
 */
/** 显式 Bean 名，避免与旧包路径下同名 {@code @Configuration} 的 class 残留（需 {@code mvn clean}）时默认名冲突。 */
@Configuration("advisorVisualizedMemoryAdvisorConfig")
public class VisualizedMemoryAdvisorConfig {

    @Bean
    public VisualizedMemoryAdvisor visualizedMemoryAdvisor(
            VisualizedMemoryManager memoryManager,
            ChatMemory shortTermMemory,
            @Qualifier("dashScopeChatModel") ChatModel dashScopeChatModel,
            @Qualifier("diaryVectorStore") VectorStore diaryVectorStore,
            @Qualifier("knowledgeVectorStore") VectorStore knowledgeVectorStore,
            ChatMessageStore chatMessageStore) {
        return new VisualizedMemoryAdvisor(
                memoryManager,
                shortTermMemory,
                dashScopeChatModel,
                diaryVectorStore,
                knowledgeVectorStore,
                chatMessageStore
        );
    }
}
