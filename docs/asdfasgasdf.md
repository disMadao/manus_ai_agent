OpenFriend　　　　　　　　　　　　　　　　　　　　　　　　　　　 2026.02-2026.03
项目简介： 基于 Spring AI + PostgreSQL + PGVector + Tool Calling 实现的通用型智能体后端，支持多轮对话、可视化长期记忆、检索增强生成、多工具调用及基于 ReAct 的自主规划智能体。
主要工作：
1）统一网关与会话编排： 设计并实现 AgentGateway 统一路由层，支持 normal / thinking / super 多模式对话编排，打通同步、SSE 流式、多渠道接入及会话持久化能力。
2）长期记忆机制： 基于 Spring AI Advisor + ChatMemory 实现可视化记忆系统，将 SOUL.md、memory.md、当日日记与历史会话统一注入上下文；支持会话级记忆重载、消息持久化，并在阈值触发后自动进行日记坍缩与偏好更新，形成可扩展长期上下文。
3）RAG 检索增强： 基于 Spring AI RAG + PGVector 构建知识库与日记双向量检索链路，实现查询改写、相似度检索、上下文拼装；在向量入库侧加入去重同步、批量写入与 PostgreSQL advisory lock 机制，保证索引更新的稳定性。
4）工具调用体系： 基于 Spring AI @Tool 与统一注册机制，实现搜索、网页抓取、文件操作、终端执行、资源下载、PDF 生成、记忆工作区读写等工具能力；同时以 stdio 模式接入高德地图 MCP，并支持自建 image-search MCP server 调用。
5）自主规划智能体： 基于 ReAct 思路实现 BaseAgent、ToolCallAgent、ManusAgent 分层智能体架构，自主维护消息上下文与工具执行结果，支持多步规划、工具选择、终止控制及最终结果流式输出。
6）Skills 能力扩展： 接入 FileSystemSkillRegistry 与 SkillPromptAugmentAdvisor，支持本地 Skills 目录加载；实现 SkillInstallTool，打通 SkillHub 技能搜索、下载、安装与本地占位降级流程。
7）用户行为记忆接入： 实现基于 ActivityWatch REST API 的行为采集与心跳调度机制，按应用维度聚合本机使用时长，定时生成 workspace/memory/activity 下的 Markdown 摘要，为智能体提供外部行为记忆输入。