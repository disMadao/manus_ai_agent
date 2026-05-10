# Skill 系统（按需加载版本）

Skill 系统允许你创建和使用预定义的"技能"来处理特定类型的任务。每个 Skill 本质上是一段结构化的提示词模板，可以包含指令、工具调用限制和 Python 脚本。

## 核心特性

### 按需加载
- **启动时**：只加载 SKILL.md 的 frontmatter（元信息）
- **激活时**：用户调用 `invokeSkill` 时才加载完整内容
- **会话持久化**：激活后的 skill 在整个会话中保持有效

### 生命周期
| 事件 | 行为 |
|------|------|
| 应用启动 | 扫描目录，只加载 frontmatter |
| 用户调用 invokeSkill | 加载完整内容，激活到会话 |
| 后续对话 | skill 内容自动注入 system prompt |
| 用户调用 deactivateSkill | 从会话移除 |
| 会话结束/应用重启 | 自动清空所有激活的 skills |

## 目录结构

Skills 存放在以下目录（按优先级从低到高）：

1. `~/.manus/skills/` - 用户级
2. `workspace/skills/` - 项目级
3. `.manus/skills/` - 项目级备选
4. 自定义目录（通过配置 `skill.dirs`）

每个 skill 是一个独立的目录，包含 `SKILL.md` 文件：

```
workspace/skills/
├── my-skill/
│   ├── SKILL.md        # skill 定义文件（必需）
│   ├── tools/          # Python 脚本（可选）
│   ├── prompts/        # 提示词模板（可选）
│   └── ...
└── another-skill/
    └── SKILL.md
```

## SKILL.md 格式

SKILL.md 使用 YAML frontmatter + Markdown body 格式：

```markdown
---
name: my-skill
description: |
  这个 skill 做什么
when_to_use: 什么情况下使用这个 skill
argument-hint: [参数提示]
allowed-tools: [Read, Write, Bash]  # 可选：限制可用工具
user-invocable: true                 # 默认 true
disable-model-invocation: false      # 默认 false
version: 1.0.0
---

# Skill 名称

这里是 skill 的主体内容，模型会按照这里的指令执行。

支持变量替换：
- ${ARGUMENTS} - 用户传入的参数
- ${SKILL_DIR} - skill 所在目录
- ${SESSION_ID} - 当前会话 ID

## 如果需要执行 Python 脚本

使用 Bash 工具执行：

```bash
python3 ${SKILL_DIR}/tools/my_script.py --arg ${ARGUMENTS}
```
```

## Frontmatter 字段说明

| 字段 | 类型 | 说明 |
|-----|------|------|
| `name` | string | skill 的唯一标识（默认为目录名） |
| `description` | string | 描述，帮助模型理解何时使用 |
| `when_to_use` | string | 触发条件描述 |
| `allowed-tools` | array | 限制此 skill 只能使用的工具列表 |
| `arguments` | array | 参数名列表，支持 `${ARG_NAME}` 替换 |
| `argument-hint` | string | 参数使用提示 |
| `user-invocable` | boolean | 是否允许用户直接调用（默认 true） |
| `disable-model-invocation` | boolean | 是否禁止模型调用（默认 false） |
| `version` | string | 版本号 |
| `context` | string | 执行上下文：`inline`（默认）或 `fork` |
| `model` | string | 使用特定模型执行 |
| `paths` | array | 条件激活的路径模式（如 `**/*.py`）|

## API 使用

### 工具列表

| 工具 | 说明 |
|------|------|
| `invokeSkill(skillName, arguments)` | 激活一个 skill（会话级持久化）|
| `deactivateSkill(skillName)` | 移除已激活的 skill |
| `listSkills()` | 列出所有可用和已激活的 skills |
| `executeSkillScript(skillName, command, params)` | 执行 skill 中的脚本 |

### 使用示例

```
# 激活 skill
invokeSkill(skillName="tong-jincheng-perspective")

# 之后的对话中，skill 自动生效
# 无需每次重复调用

# 如果要移除
deactivateSkill(skillName="tong-jincheng-perspective")
```

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                     SkillLoader                             │
│  启动时扫描目录，只加载 frontmatter → SkillMetadata         │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  SkillSessionManager                        │
│  会话级管理：激活时加载完整内容 → ActiveSkill               │
│  - activateSkill(): 激活并缓存                              │
│  - deactivateSkill(): 用户显式移除                          │
│  - buildActiveSkillsPrompt(): 生成 system prompt 片段       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    SkillInvokeTool                          │
│  暴露给模型的工具：invokeSkill, deactivateSkill, listSkills │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     OpenFriend (Agent)                      │
│  buildFullSystemPrompt() 自动注入已激活的 skills 内容       │
└─────────────────────────────────────────────────────────────┘
```

## 配置

在 `application.yml` 中配置：

```yaml
skill:
  # 额外的 skill 扫描目录
  dirs: /path/to/custom/skills
  sandbox:
    # 是否启用 conda 沙箱（推荐开启）
    enabled: true
    # conda 环境名称
    conda-env: skill-sandbox
    # 脚本执行超时时间（秒）
    timeout: 120
```

## 兼容 Claude Code

本 Skill 系统兼容 Claude Code 的 SKILL.md 格式：

- 支持 `${CLAUDE_SKILL_DIR}` 变量（映射到 `${SKILL_DIR}`）
- 支持 `${CLAUDE_SESSION_ID}` 变量
- 支持 Claude Code 的 frontmatter 字段

现有的 Claude Code skills 可以直接复制到 `workspace/skills/` 目录下使用。
