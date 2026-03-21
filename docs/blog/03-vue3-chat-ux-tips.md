# Vue3 聊天前端体验优化：Shift+Enter 换行与文本回显排版

在开发 AI Agent 的 Web 聊天室时，一些看似不起眼的交互细节往往决定了用户体验的成败。本文记录了在 Vue3 前端项目中修复聊天输入框换行与消息展示排版的两个关键细节。

## 痛点 1：无法使用 Shift+Enter 换行

在大多数现代聊天软件中，按下 `Enter` 键发送消息，按下 `Shift + Enter` 则在输入框内换行。但在我们的初始实现中，绑定了 `@keydown.enter="sendMessage"`，导致用户无论是按 `Enter` 还是 `Shift+Enter`，都会直接把没写完的消息发送出去。

### 解决方案：利用 Vue 的精确修饰符

Vue 提供了极其优雅的事件修饰符，我们可以使用 `.exact` 来严格控制按键组合。

```html
<!-- 错误做法：任何包含 Enter 的组合都会触发 -->
<textarea @keydown.enter.prevent="sendMessage"></textarea>

<!-- 正确做法：只有纯粹按下 Enter 且不包含 Shift/Ctrl/Alt 时才触发 -->
<textarea @keydown.enter.exact.prevent="sendMessage"></textarea>
```
配合 textarea 原生的特性，当按下 `Shift + Enter` 时，事件不再被拦截，textarea 会自然地换行，完美实现了预期的交互。

## 痛点 2：发送后换行符丢失，消息变成一坨

用户在输入框里精心排版了多行文本，点击发送后，气泡里显示的消息却挤成了一行。这是因为 HTML 默认会合并连续的空白符并将换行符视为空格。

### 解决方案：CSS 的 `white-space` 属性

针对渲染聊天气泡内容的 CSS 类，我们需要加上保留空白和换行的样式规则：

```css
.message-content {
  /* 核心修复：保留换行和空白符，同时允许文本自动换行 */
  white-space: pre-wrap;
  
  /* 防止长英文单词或链接撑破容器 */
  word-break: break-word;
}
```

* `white-space: pre-wrap;`：既保留了源文本中的回车换行符（`\n`）以及连续空格，又能在行尾空间不足时自动折行，是展示用户纯文本输入的最优解。

通过这两行简单的代码，聊天室的输入与展示体验得到了质的提升。
