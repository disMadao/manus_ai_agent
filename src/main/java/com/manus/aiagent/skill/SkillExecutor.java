package com.manus.aiagent.skill;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Skill 脚本执行器
 * 负责在沙箱环境中安全地执行 skill 中的脚本命令
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillExecutor {

    /**
     * 沙箱 conda 环境名称
     */
    @Value("${skill.sandbox.conda-env:skill-sandbox}")
    private String condaEnvName;

    /**
     * 脚本执行超时时间（秒）
     */
    @Value("${skill.sandbox.timeout:120}")
    private int executionTimeout;

    /**
     * 是否启用 conda 沙箱
     */
    @Value("${skill.sandbox.enabled:true}")
    private boolean sandboxEnabled;

    /**
     * 临时文件目录
     */
    @Value("${skill.sandbox.temp-dir:}")
    private String tempDir;

    /**
     * 在沙箱中执行脚本
     *
     * @param skill   skill 对象
     * @param command 命令
     * @param params  额外参数
     * @return 执行结果
     */
    public String executeInSandbox(Skill skill, String command, Map<String, String> params) {
        log.info("在沙箱中执行命令: {}", command);

        try {
            // 1. 替换命令中的变量
            String processedCommand = processCommand(command, skill, params);
            log.debug("处理后的命令: {}", processedCommand);

            // 2. 检查并创建沙箱环境
            if (sandboxEnabled && !checkCondaEnvExists()) {
                String createResult = createCondaEnv();
                log.info("创建 conda 环境结果: {}", createResult);
            }

            // 3. 执行命令
            String result;
            if (sandboxEnabled && isPythonCommand(processedCommand)) {
                result = executeInCondaEnv(processedCommand, skill.getSkillDir());
            } else {
                result = executeDirectly(processedCommand, skill.getSkillDir());
            }

            return result;

        } catch (Exception e) {
            log.error("执行脚本失败", e);
            return "执行失败: " + e.getMessage();
        }
    }

    /**
     * 处理命令中的变量替换
     */
    private String processCommand(String command, Skill skill, Map<String, String> params) {
        String result = command;

        // 替换 ${CLAUDE_SKILL_DIR} 和 ${SKILL_DIR}
        if (StrUtil.isNotBlank(skill.getSkillDir())) {
            result = result.replace("${CLAUDE_SKILL_DIR}", skill.getSkillDir());
            result = result.replace("${SKILL_DIR}", skill.getSkillDir());
        }

        // 替换自定义参数
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                result = result.replace("${" + entry.getKey() + "}", entry.getValue());
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }

        return result;
    }

    /**
     * 检查 conda 环境是否存在
     */
    private boolean checkCondaEnvExists() {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            if (isWindows()) {
                pb.command("cmd", "/c", "conda", "env", "list");
            } else {
                pb.command("bash", "-c", "conda env list");
            }

            Process process = pb.start();
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);

            if (completed) {
                String output = new String(process.getInputStream().readAllBytes());
                return output.contains(condaEnvName);
            }
            return false;

        } catch (Exception e) {
            log.warn("检查 conda 环境失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 创建 conda 沙箱环境
     */
    private String createCondaEnv() {
        log.info("创建 conda 沙箱环境: {}", condaEnvName);

        try {
            List<String> commands = new ArrayList<>();
            
            // 创建基础环境
            String createCmd = String.format("conda create -y -n %s python=3.10", condaEnvName);
            
            ProcessBuilder pb = new ProcessBuilder();
            if (isWindows()) {
                pb.command("cmd", "/c", createCmd);
            } else {
                pb.command("bash", "-c", createCmd);
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug(line);
                }
            }

            boolean completed = process.waitFor(300, TimeUnit.SECONDS);
            
            if (completed && process.exitValue() == 0) {
                // 安装常用包
                installCommonPackages();
                return "环境创建成功";
            } else {
                return "环境创建失败: " + output;
            }

        } catch (Exception e) {
            log.error("创建 conda 环境失败", e);
            return "创建失败: " + e.getMessage();
        }
    }

    /**
     * 安装常用 Python 包
     */
    private void installCommonPackages() {
        log.info("安装常用 Python 包...");
        
        String[] packages = {
            "pyyaml",
            "pillow",
            "exifread",
            "chardet"
        };

        for (String pkg : packages) {
            try {
                String installCmd = String.format("conda run -n %s pip install %s", condaEnvName, pkg);
                
                ProcessBuilder pb = new ProcessBuilder();
                if (isWindows()) {
                    pb.command("cmd", "/c", installCmd);
                } else {
                    pb.command("bash", "-c", installCmd);
                }
                
                Process process = pb.start();
                process.waitFor(60, TimeUnit.SECONDS);
                log.debug("安装 {} 完成", pkg);
                
            } catch (Exception e) {
                log.warn("安装 {} 失败: {}", pkg, e.getMessage());
            }
        }
    }

    /**
     * 在 conda 环境中执行命令
     */
    private String executeInCondaEnv(String command, String workDir) {
        log.info("在 conda 环境 {} 中执行: {}", condaEnvName, command);

        try {
            // 转换 python3 为 python（conda 环境中）
            String processedCmd = command.replace("python3 ", "python ");
            
            // 构建完整命令
            String fullCmd = String.format("conda run -n %s %s", condaEnvName, processedCmd);

            ProcessBuilder pb = new ProcessBuilder();
            if (isWindows()) {
                pb.command("cmd", "/c", fullCmd);
            } else {
                pb.command("bash", "-c", fullCmd);
            }

            // 设置工作目录
            if (StrUtil.isNotBlank(workDir)) {
                pb.directory(new File(workDir));
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(executionTimeout, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                return "执行超时（" + executionTimeout + "秒）";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return "执行失败（退出码 " + exitCode + "）:\n" + output;
            }

            return output.toString();

        } catch (Exception e) {
            log.error("执行命令失败", e);
            return "执行异常: " + e.getMessage();
        }
    }

    /**
     * 直接执行命令（非 Python）
     */
    private String executeDirectly(String command, String workDir) {
        log.info("直接执行命令: {}", command);

        try {
            ProcessBuilder pb = new ProcessBuilder();
            if (isWindows()) {
                pb.command("cmd", "/c", command);
            } else {
                pb.command("bash", "-c", command);
            }

            if (StrUtil.isNotBlank(workDir)) {
                pb.directory(new File(workDir));
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(executionTimeout, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                return "执行超时";
            }

            return output.toString();

        } catch (Exception e) {
            log.error("执行命令失败", e);
            return "执行异常: " + e.getMessage();
        }
    }

    /**
     * 判断是否为 Python 命令
     */
    private boolean isPythonCommand(String command) {
        String lower = command.toLowerCase().trim();
        return lower.startsWith("python") || lower.startsWith("pip");
    }

    /**
     * 判断是否为 Windows 系统
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * 获取临时目录
     */
    private String getTempDir() {
        if (StrUtil.isNotBlank(tempDir)) {
            return tempDir;
        }
        return System.getProperty("user.dir") + "/tmp/skill-sandbox";
    }

    /**
     * 清理临时文件
     */
    public void cleanupTempFiles() {
        try {
            Path tempPath = Paths.get(getTempDir());
            if (Files.exists(tempPath)) {
                FileUtil.del(tempPath.toFile());
                log.info("清理临时文件: {}", tempPath);
            }
        } catch (Exception e) {
            log.warn("清理临时文件失败: {}", e.getMessage());
        }
    }
}
