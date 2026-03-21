package com.manus.aiagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 网页抓取工具
 */
public class WebScrapingTool {

    @Tool(description = "Scrape the text content of a web page")
    public String scrapeWebPage(@ToolParam(description = "URL of the web page to scrape") String url) {
        try {
            Document document = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(10000)
                    .get();
            document.select("script, style, nav, footer, header, iframe, noscript").remove();
            String text = document.body() != null ? document.body().text() : document.text();
            if (text.length() > 5000) {
                text = text.substring(0, 5000) + "...(内容已截断)";
            }
            return text;
        } catch (Exception e) {
            return "Error scraping web page: " + e.getMessage();
        }
    }
}
