# Plan-05 前端（Web UI）

> 用户唯一直接接触的层。三栏布局：会话列表 / 对话区 / Workspace 文件树+编辑器。
>
> 关联：[plan-00](./plan-00-overview.md) · [plan-01 /auth /me /me/quota](./plan-01-foundation.md) · [plan-03 /workspace/files/*](./plan-03-workspace.md) · [plan-04 /sessions /chat/sse](./plan-04-chat-agent.md) · [spec.md](./spec.md) §FR-3, §FR-4

---

## 0. 模块边界

### 做什么

1. 注册 / 登录 / 登出 + JWT 自动 refresh 拦截器；
2. 会话列表（增删改查 + 多 tab 切换）；
3. 对话区（流式 SSE、工具调用展示、中断 / 重新生成、附件上传）；
4. **Workspace 文件树**（虚拟列表、懒加载、右键操作、拖拽）；
5. **Monaco 编辑器**：在线编辑、ETag 冲突合并 UI；
6. `/workspace/files/watch` SSE 客户端，把变更应用到文件树与编辑器；
7. 个人中心：配额查看、修改密码（后置）、退出登录；
8. 错误提示统一组件（按错误码映射文案）。

### 不做什么

- 不写 admin 后台（后置 / 单独模块）；
- 不写 i18n（v1 中文）；
- 不做主题切换（v1 单一暗色或浅色）。

---

## 1. 对外契约（消费谁的）

| 来自 | 用什么 |
|------|-------|
| plan-01 | `POST /auth/register|login|refresh|logout`、`GET /me`、`GET /me/quota` |
| plan-03 | `/workspace/files/*` 全部 + `/workspace/files/watch` SSE |
| plan-04 | `/sessions/*`、`/sessions/{id}/messages`、`/sessions/{id}/chat/sse`、`/sessions/{id}/chat/interrupt`、`/me/running` |

**任何变更需要先有 OpenAPI / SSE schema 落地**（plan-02 §11 / plan-03 §10 / plan-04 §11 要求各模块第 1 周交付），前端据此 mock 并行开发。

---

## 2. 技术栈

| 项 | 选型 |
|----|------|
| 框架 | Vue 3.5+ Composition API + `<script setup>` + TypeScript |
| 构建 | Vite 5 |
| 状态 | Pinia |
| 路由 | vue-router 4 |
| HTTP | axios（统一拦截器：JWT 注入、401 触发 refresh、错误码映射） |
| SSE | 原生 `EventSource` + 封装 hook `useSse` |
| UI 库 | **Naive UI** 或 **Element Plus**（任选其一，本 plan 默认 Naive UI 因更现代） |
| 编辑器 | `monaco-editor` + `@guolao/vue-monaco-editor`（Vue 3 适配） |
| 文件树 | `vue-virtual-tree` 或 自研基于 `vue-virtual-scroller` |
| Markdown 渲染 | `markdown-it` + `highlight.js` |
| 图标 | `@vicons/ionicons5` |
| 单测 | Vitest + Vue Test Utils |
| E2E | Playwright |

---

## 3. 页面结构与路由

```
/login                      登录页
/register                   注册页
/                           主应用（三栏）
  ├── 会话列表（左）
  ├── 对话区（中）
  │   - 上：消息流（user / assistant / tool_call / tool_result / thinking）
  │   - 下：输入框 + 附件 + 模式选择 + 发送/中断
  └── Workspace（右）
      - 文件树（顶）
      - 文件预览/编辑器（底，Monaco）
/me                         个人中心
/me/quota                   配额查看
```

**响应式**：宽屏三栏；窄屏（< 1024px）右侧 Workspace 收起为抽屉。

---

## 4. 目录结构

```
ai-agent-frontend/
  src/
    main.ts
    router/
      index.ts
    stores/
      auth.ts                # JWT、当前用户
      sessions.ts            # 会话列表 + 当前会话
      messages.ts            # 当前会话消息流
      workspace.ts           # 文件树缓存、当前打开文件、ETag、未保存草稿
      runningAgents.ts       # FR-11 当前正在跑的会话
      ui.ts                  # 全局 toast、对话框
    api/
      http.ts                # axios 实例
      auth.ts
      sessions.ts
      chat.ts                # SSE 客户端
      workspace.ts
      watch.ts               # workspace/files/watch SSE 客户端
    composables/
      useSse.ts
      useFsWatch.ts          # 订阅 watcher 并 dispatch 到 workspace store
      useEtagSave.ts         # 编辑器保存 + 冲突合并
    components/
      ChatPanel/
        MessageList.vue
        MessageBubble.vue
        ToolCallCard.vue
        ChatInput.vue
        ModeSelector.vue
      Sessions/
        SessionList.vue
        SessionItem.vue
        NewSessionDialog.vue
      Workspace/
        FileTree.vue            # 虚拟列表
        FileTreeNode.vue
        FilePreview.vue         # 图片/PDF/Markdown
        FileEditor.vue          # Monaco
        ConflictMergeDialog.vue # 三方合并 UI（简版：选 local/remote）
        UploadDropZone.vue
        RenameDialog.vue
      Common/
        ErrorBoundary.vue
        Toast.vue
        QuotaBadge.vue
    pages/
      LoginPage.vue
      RegisterPage.vue
      MainPage.vue
      MePage.vue
    types/
      api.ts                  # 后端 DTO（由 OpenAPI 生成或手写）
      sse.ts                  # SSE 事件 schema
```

---

## 5. 关键实现细节

### 5.1 axios 拦截器（auth + 错误码）

```ts
http.interceptors.request.use(cfg => {
  const t = useAuth().accessToken;
  if (t) cfg.headers.Authorization = `Bearer ${t}`;
  return cfg;
});

http.interceptors.response.use(
  r => r,
  async err => {
    const { response, config } = err;
    if (response?.data?.code === 'AUTH_TOKEN_EXPIRED' && !config.__retried) {
      config.__retried = true;
      await useAuth().refresh();
      return http(config);   // 重放
    }
    showToast(mapCodeToText(response?.data?.code) ?? '网络错误');
    return Promise.reject(err);
  }
);
```

### 5.2 SSE 客户端封装（双 SSE：chat + watch）

```ts
// composables/useSse.ts
export function useSse(url: string, handlers: Record<string, (data: any) => void>) {
  let es: EventSource | null = null;
  const open = () => {
    es = new EventSource(url, { withCredentials: true });
    for (const [evt, fn] of Object.entries(handlers)) {
      es.addEventListener(evt, e => fn(JSON.parse((e as MessageEvent).data)));
    }
    es.onerror = () => {
      es?.close();
      setTimeout(open, 1500);  // 简单重连，watch SSE 会收到 snapshot 事件
    };
  };
  const close = () => es?.close();
  return { open, close };
}
```

**EventSource 不能自定义 Header**——JWT 通过 query string 临时鉴权 token 走 cookie（plan-01 在 `/login` 时也写一个短期 HttpOnly cookie 给 SSE 用），或者后端允许 `?access_token=` query 参数走 SSE 鉴权。

### 5.3 文件树（虚拟列表 + 懒加载）

- 数据结构：扁平 `Map<path, FileNode>`，节点存 `{ name, type, etag, mtime, size, children?: Set<path>, expanded }`；
- 滚动用 `vue-virtual-scroller`，节点只渲染可见范围；
- 点击展开 → `GET /workspace/files?path=...` 拉子节点 → 合并到 Map；
- watcher 事件：
  - `created` → 添加到父节点 children；
  - `modified` → 更新 etag/mtime；
  - `deleted` → 从父节点删；
  - `moved` → 删 from + 加 to；
  - `snapshot` → 清空所有 expanded 节点缓存，触发当前可见节点重新拉取。

### 5.4 Monaco 编辑器与 ETag 冲突

```ts
// composables/useEtagSave.ts
async function save(path: string, content: string, etag: string, actorType='user') {
  try {
    const r = await http.put(`/workspace/files/raw?path=${enc(path)}`, content, {
      headers: { 'If-Match': etag, 'X-Actor-Type': actorType, 'Content-Type': 'text/plain' }
    });
    return { ok: true, newEtag: r.headers.etag };
  } catch (e: any) {
    if (e.response?.data?.code === 'FILE_CONFLICT') {
      // 触发三方合并对话框
      const merge = await openMergeDialog({
        path,
        local: content,
        remote: e.response.data.currentContent,
        remoteEtag: e.response.data.currentEtag
      });
      if (merge.choice === 'cancel') return { ok: false };
      return save(path, merge.text, e.response.data.currentEtag, actorType);
    }
    throw e;
  }
}
```

**三方合并 UI v1 简版**：弹窗，左右两栏显示 local / remote，提供"用本地覆盖" / "用远端覆盖" / "手动合并"（手动合并用 Monaco diff editor）三个按钮。

### 5.5 watcher 来源提示

收到 `event: modified actor: { type:'agent', sessionId:'sid-A' }` 时：

- 在文件树该文件旁显示一个小圆点 + tooltip "会话 [A 的名字] 的 Agent 修改了此文件"；
- 如果该文件正在编辑器打开：
  - 编辑器内容没改 → 静默 reload，toast 提示"已自动同步会话 X 的更新"；
  - 编辑器内容已改 → 在编辑器顶部黄条："此文件已被会话 X 的 Agent 修改，[查看远端] [合并] [忽略]"。

需要拉 sessionId → 会话名的映射；由 sessions store 维护。

### 5.6 多会话 tab 与并发提示

- 会话列表项右侧显示徽标：`●` 表示当前 Agent 在跑；
- 标题栏右上角显示全局徽标：`X 个会话正在运行（X/3）`；
- 数据源：`/me/running` 在收到任何 SSE `done` / `interrupt` 事件后刷新一次。

### 5.7 中断与重新生成

- 输入框旁的"发送"按钮在 Agent 运行中变为"中断"；点击调用 `/chat/interrupt`；
- 最后一条 assistant 消息悬停显示"重新生成"按钮 → `/chat/regenerate`。

### 5.8 上传体验

- 文件树右键 / 顶部按钮 / 拖拽空白处 → 三种入口；
- 大文件（>16MB）自动走分片，进度条嵌在文件项预览；
- 上传过程中显示在文件树（占位 + 进度），完成后由 watcher 事件确认替换。

---

## 6. 状态管理样例（workspace store）

```ts
// stores/workspace.ts
export const useWorkspaceStore = defineStore('workspace', () => {
  const tree = ref(new Map<string, FileNode>());
  const opened = ref<{ path: string; content: string; etag: string; dirty: boolean } | null>(null);

  function applyEvent(ev: WatchEvent) {
    switch (ev.type) {
      case 'created':   tree.value.set(ev.path, toNode(ev)); break;
      case 'modified':  patchNode(ev.path, ev); maybeReloadEditor(ev); break;
      case 'deleted':   tree.value.delete(ev.path); break;
      case 'moved':     tree.value.delete(ev.from); tree.value.set(ev.to, toNode(ev)); break;
      case 'snapshot':  reloadVisibleSubtrees(); break;
    }
  }
  // ...
});
```

---

## 7. 配置（Vite env）

```
VITE_API_BASE=http://localhost:8123
VITE_ENABLE_MOCK=true             # 联调阶段
```

---

## 8. 测试

| 项 | 用例 |
|----|------|
| 单元（Vitest） | http 拦截器、useEtagSave 冲突分支、tree applyEvent |
| 组件 | FileTree 渲染 10k 节点 < 100ms（虚拟列表） |
| E2E（Playwright） | 登录→新建会话→发消息→等待 SSE→看到 assistant 内容→在文件树看到 Agent 写的新文件 |
| E2E | 编辑文件→保存→sidecar mock 内文件内容一致 |
| E2E | 同时打开 2 个 tab，A 改文件→B 在 ≤ 2s 内提示 |

---

## 9. 验收清单（DoD）

- [ ] 注册/登录/refresh 自动续期
- [ ] 三栏 UI（>1024px）+ 抽屉（<1024px）
- [ ] 会话列表 CRUD + 切换
- [ ] 对话 SSE：thinking / tool_call / tool_result / message / done 五类事件正确渲染
- [ ] 中断生效（UI 状态回退、调接口）
- [ ] 文件树 watcher 接通：created/modified/deleted/moved 立即反映
- [ ] Monaco 在线编辑 + Ctrl+S 保存
- [ ] ETag 409 触发三方合并 UI（v1 简版可用）
- [ ] 大文件下载（非编辑）、上传分片进度
- [ ] 路径越界、配额超限、并发 429 全部有友好提示
- [ ] /me/quota 面板能看活跃 sandbox / 当日 token / workspace 占用

---

## 10. 与其他模块的交互（清单）

| 谁 | 我消费什么 | 关键约束 |
|----|----------|---------|
| plan-01 | /auth, /me, /me/quota | JWT in `Authorization` + 自动 refresh；SSE 走 query token 或 HttpOnly cookie |
| plan-03 | /workspace/files/* + /watch SSE | ETag 通过 `ETag`/`If-Match` 头透传 |
| plan-04 | /sessions, /chat/sse | SSE 事件 schema 见 spec §9.1 |

---

## 11. 早期产出物（让后端可独立验收）

第 1 周：

1. UI 骨架（路由 + 三栏 + Naive UI 主题）；
2. mock 模式（基于 MSW 或本地 stub）跑通登录 → 会话列表 → 假数据消息流 → 假文件树；
3. 与后端共建 OpenAPI / SSE schema 文件（统一放 `docs/openapi/` 下）。

---

## 12. 已知风险与缓解

| 风险 | 缓解 |
|------|------|
| EventSource 不支持自定义 header（无法发 JWT） | 后端 SSE 端点接受 `?access_token=` query；或登录时下发短期 HttpOnly cookie 专给 SSE 用 |
| Monaco 体积大（~3MB gzip） | 路由级懒加载，仅在打开编辑器时加载 |
| 文件树万级节点卡顿 | 虚拟滚动 + 懒加载子节点 + 按目录分页 |
| SSE 长连接被代理切断 | 30s 心跳 event；前端 onerror 自动重连 + snapshot 全量刷 |
| 多 tab 重复订阅 SSE 浪费连接 | 用 BroadcastChannel + SharedWorker 二期再做，v1 接受多连接（plan-03 单用户上限 5） |
