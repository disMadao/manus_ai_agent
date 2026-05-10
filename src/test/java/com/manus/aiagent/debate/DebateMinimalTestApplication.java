package com.manus.aiagent.debate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * 辩论集成测试专用最小启动类：只扫描 {@code com.manus.aiagent.debate}，
 * 避免拉起全量应用（PgVector、ChatMessageStore 等依赖本机 PostgreSQL）。
 * <p>
 * 正式环境仍使用 {@link com.manus.aiagent.AiAgentApplication}，本类不替代主启动。
 */
@SpringBootApplication(scanBasePackages = "com.manus.aiagent.debate")
@Import(LiteAgentImportConfig.class)
public class DebateMinimalTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(DebateMinimalTestApplication.class, args);
    }
}
