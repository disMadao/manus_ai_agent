package com.manus.aiagent.memory;

import com.manus.aiagent.app.LoveApp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class VisualizedMemoryTest {

    @Autowired
    private LoveApp loveApp;

    @Test
    public void testPromptDrivenMemoryCollapse() throws InterruptedException {
        // 给当前测试用户一个固定的 ID
        String chatId = "user-test-prompt-memory-01";

        System.out.println("========== 正在进行第 1 轮对话 ==========");
        String reply1 = loveApp.doChat("你好，我是一个在新加坡工作的程序员，最近压力很大，总是失眠。", chatId);
        System.out.println("AI: " + reply1);

        System.out.println("\n========== 正在进行第 2 轮对话 ==========");
        String reply2 = loveApp.doChat("因为圈子太小了，家里人又一直催婚，但我实在不知道怎么去认识新朋友，更别提谈恋爱了。", chatId);
        System.out.println("AI: " + reply2);

        System.out.println("\n========== 正在进行第 3 轮对话 (即将突破阈值，触发坍缩) ==========");
        String reply3 = loveApp.doChat("你说的对。那你觉得我周末应该去参加什么类型的活动比较好？我比较喜欢安静一点的。", chatId);
        System.out.println("AI: " + reply3);

        // 此时 currentHistory 已经达到或超过 4 条，触发了 CompletableFuture.runAsync
        System.out.println("\n========== 触发后台记忆坍缩，等待大模型重写 memory.md (约 15-20 秒) ==========");
        
        // 挂起主线程，防止测试直接结束导致后台写文件线程被强杀
        Thread.sleep(40000);

        System.out.println("\n========== 测试完成！请检查项目根目录的 /memory 文件夹 ==========");
    }
}