package com.manus.aiagent.debate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 辩论 MVP 端到端：调用 DashScope，耗时与费用较高，按需单独执行。
 * <p>
 * 使用 {@link DebateMinimalTestApplication}，避免全量启动依赖 PostgreSQL。
 */
@SpringBootTest(classes = DebateMinimalTestApplication.class)
@ActiveProfiles({"local", "debate-test"})
class DebateIntegrationTest {

//    private static final String TOPIC = "恋爱是不是人走向成熟的必要经历";//不要删除这行注释！！！！

    private static final String TOPIC = "人是不会真正做出改变的，无论你怎么尝试、怎么努力";

    @Autowired
    private DebateCoordinator debateCoordinator;

    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    void runLoveMaturityDebate() throws Exception {
        String debateId = "debate_it_" + System.currentTimeMillis();
        debateCoordinator.runDebate(debateId, TOPIC);

        Path dir = Path.of(System.getProperty("user.dir"), "workspace", "debate", debateId);
        assertTrue(Files.isDirectory(dir), "输出目录应存在: " + dir);
        assertTrue(Files.exists(dir.resolve("09_transcript.txt")), "应有 transcript");
        assertTrue(Files.exists(dir.resolve("01_pro_team_prep.txt")), "应有正方准备");
        assertTrue(Files.exists(dir.resolve("02_con_team_prep.txt")), "应有反方准备");
        assertTrue(Files.exists(dir.resolve("06_free_debate.txt")), "应有自由辩论");
    }
}
