package com.manus.aiagent.tools;

import cn.hutool.core.io.FileUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 故障诊断搜索工具 —— 在 workspace/tmp 下的运维数据文件中检索信息
 */
public class DiagnosisSearchTool {

    private final String dataDir;

    public DiagnosisSearchTool(String dataDir) {
        this.dataDir = dataDir;
    }

    @Tool(description = "列出诊断数据目录下所有可用的数据文件（工单、日志、监控、安全组等），返回文件名列表")
    public String listDataFiles() {
        File dir = new File(dataDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return "数据目录不存在: " + dataDir;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".txt"));
        if (files == null || files.length == 0) {
            return "数据目录下没有找到任何 txt 文件";
        }
        StringBuilder sb = new StringBuilder("可用数据文件列表:\n");
        for (File f : files) {
            sb.append("  - ").append(f.getName()).append(" (").append(f.length()).append(" bytes)\n");
        }
        return sb.toString();
    }

    @Tool(description = "读取诊断数据目录下指定文件的全部内容，比如读取 work_orders.txt、system_logs.txt、monitoring_data.txt、security_groups.txt、instance_info.txt、network_diagnosis.txt 等")
    public String readDataFile(
            @ToolParam(description = "要读取的文件名，例如 work_orders.txt") String fileName) {
        String filePath = dataDir + "/" + fileName;
        File file = new File(filePath);
        if (!file.exists()) {
            return "文件不存在: " + fileName;
        }
        try {
            return FileUtil.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "读取文件失败: " + e.getMessage();
        }
    }

    @Tool(description = "在诊断数据目录的所有txt文件中搜索包含指定关键词的行，返回匹配结果（文件名+行号+内容）。可用于搜索实例ID、错误信息、端口号等关键词。")
    public String searchKeyword(
            @ToolParam(description = "要搜索的关键词，例如 ins-abc123、SSH、port 22、Connection timed out 等") String keyword) {
        File dir = new File(dataDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return "数据目录不存在: " + dataDir;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".txt"));
        if (files == null || files.length == 0) {
            return "数据目录下没有找到任何 txt 文件";
        }

        List<String> matches = new ArrayList<>();
        for (File file : files) {
            try {
                List<String> lines = FileUtil.readLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).toLowerCase().contains(keyword.toLowerCase())) {
                        matches.add(String.format("[%s:%d] %s", file.getName(), i + 1, lines.get(i).trim()));
                    }
                }
            } catch (Exception e) {
                matches.add("[" + file.getName() + "] 读取失败: " + e.getMessage());
            }
        }

        if (matches.isEmpty()) {
            return "未找到包含关键词 \"" + keyword + "\" 的记录";
        }
        return "搜索 \"" + keyword + "\" 共找到 " + matches.size() + " 条匹配:\n" + String.join("\n", matches);
    }

    @Tool(description = "在诊断数据目录的指定文件中搜索包含关键词的行，返回匹配行及其前后各N行的上下文")
    public String searchWithContext(
            @ToolParam(description = "要搜索的文件名") String fileName,
            @ToolParam(description = "要搜索的关键词") String keyword,
            @ToolParam(description = "上下文行数（前后各取多少行）") int contextLines) {
        String filePath = dataDir + "/" + fileName;
        File file = new File(filePath);
        if (!file.exists()) {
            return "文件不存在: " + fileName;
        }
        try {
            List<String> lines = FileUtil.readLines(file, StandardCharsets.UTF_8);
            List<String> results = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).toLowerCase().contains(keyword.toLowerCase())) {
                    int start = Math.max(0, i - contextLines);
                    int end = Math.min(lines.size() - 1, i + contextLines);
                    results.add("--- 匹配位置: 第" + (i + 1) + "行 ---");
                    for (int j = start; j <= end; j++) {
                        String prefix = (j == i) ? ">>>" : "   ";
                        results.add(String.format("%s %4d| %s", prefix, j + 1, lines.get(j)));
                    }
                    results.add("");
                }
            }
            if (results.isEmpty()) {
                return "在 " + fileName + " 中未找到关键词 \"" + keyword + "\"";
            }
            return String.join("\n", results);
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }
}
