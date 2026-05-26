package com.manus.aiagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class WebSearchToolTest {

    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Test
    void searchWeb() {
//        System.out.println("执行这里");
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
//        System.out.println("实例化正常");
        String query = "程序员 example.com";
        String result = webSearchTool.searchWeb(query);
        System.out.println(result);
        Assertions.assertNotNull(result);
    }
}
