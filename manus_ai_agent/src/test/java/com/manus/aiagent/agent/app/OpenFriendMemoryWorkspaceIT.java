package com.manus.aiagent.agent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

/**
 * OpenFriend：用自然语言用户 prompt 覆盖「记忆 / 日记 / 人设」相关常见说法。
 * 需可用模型与密钥；是否发起 tool call 由模型决定，断言以「有合理解答」为主。
 */
@SpringBootTest
class OpenFriendMemoryWorkspaceIT {

    @Resource
    private OpenFriend openFriend;

    @Test
    @DisplayName("长对话里顺带问昨天日记写了什么")
    void userMentionsReadingYesterdayDiaryInLongMessage() {
        String chatId = UUID.randomUUID().toString();
        String message = """
                今天加班好累，晚上想早点睡。对了，你帮我看一眼我昨天那篇日记里写了啥？
                不用全文念给我，挑一两句你觉得重要的就行。如果昨天没记日记就说一声。
                """;
        String answer = openFriend.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isBlank(), "应有模型回复");
    }

    @Test
    @DisplayName("直接问前天日记里心情怎么样")
    void userAsksDayBeforeYesterdayMood() {
        String chatId = UUID.randomUUID().toString();
        String message = """
                我脑子有点乱。你帮我翻翻前天那天的日记，看看我那天整体是偏焦虑还是偏平静？
                要是前天根本没记，就直说没有，别猜。
                """;
        String answer = openFriend.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isBlank(), "应有模型回复");
    }

    @Test
    @DisplayName("用「3天前」这种口语要日记摘要")
    void userAsksThreeDaysAgoDiary() {
        String chatId = UUID.randomUUID().toString();
        String message = "我忘了周三左右自己记过啥——你帮我看看三天前那篇日记，用两三句话概括主题就行。";
        String answer = openFriend.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isBlank(), "应有模型回复");
    }

    @Test
    @DisplayName("对照 memory 里的角色与偏好再聊")
    void userWantsToAlignWithMemoryBeforeChatting() {
        String chatId = UUID.randomUUID().toString();
        String message = """
                我准备继续用现在这个「角色」跟你聊两句。
                你先帮我扫一眼我 workspace 里那份 memory（memory.md）里和角色/偏好相关的部分，
                用一句话告诉我你读到的重点，再开始正常回我。
                """;
        String answer = openFriend.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isBlank(), "应有模型回复");
    }

    @Test
    @DisplayName("问 SOUL / 人设里怎么写行为准则的")
    void userAsksAboutSoulPersona() {
        String chatId = UUID.randomUUID().toString();
        String message = """
                我想确认一下：我本地那份 SOUL 人设里，关于「界限」或隐私是怎么写的？
                你按文件里的说法概括一下，别自己编规则。
                """;
        String answer = openFriend.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isBlank(), "应有模型回复");
    }

    @Test
    @DisplayName("要看日记地图/最近几天有没有记")
    void userAsksDiaryMapOrRecentDays() {
        String chatId = UUID.randomUUID().toString();
        String message = """
                我最近记日记断断续续的。你帮我从 memory 里的「日记地图」看看，
                最近几天哪些日子有一句摘要？不用展开正文。
                """;
        String answer = openFriend.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isBlank(), "应有模型回复");
    }

    @Test
    @DisplayName("点名具体日期（yyyy-MM-dd）读日记")
    void userAsksSpecificIsoDateDiary() {
        String chatId = UUID.randomUUID().toString();
        String message = """
                帮我读一下 2026-03-11 那天的日记正文（如果存在的话）。
                不存在就说没有，别编内容。
                """;
        String answer = openFriend.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isBlank(), "应有模型回复");
    }

    @Test
    @DisplayName("多轮：先闲聊再要读今天日记片段")
    void multiTurnThenAskTodayDiarySnippet() {
        String chatId = UUID.randomUUID().toString();
        openFriend.doChat("嗨，今天有点累，先随便聊一句：你觉得睡前少刷手机有用吗？", chatId);
        String message = """
                对了，我今天如果已经写了日记的话，你帮我从今天的日记里挑一句最能代表我状态的，
                没有就告诉我今天还没记。
                """;
        String answer = openFriend.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isBlank(), "应有模型回复");
    }

    @Test
    @DisplayName("模糊说「前几天」——模型应追问或说明需具体日期")
    void userSaysFewDaysAgoVague() {
        String chatId = UUID.randomUUID().toString();
        String message = "前几天我好像记过一段烦心事，你帮我从日记里找找？我也不确定是哪天。";
        String answer = openFriend.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isBlank(), "应有模型回复");
    }
}
