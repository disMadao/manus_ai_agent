# Spring @Qualifier 与按名注入

## 核心机制

Spring 注入 Bean 时按**类型**匹配。当同类型存在多个 Bean（如项目中同时有 `dashScopeChatModel` 和 `ollamaChatModel` 两个 `ChatModel`），就会报 `NoUniqueBeanDefinitionException`。

解决办法有三种，优先级从高到低：

### 1. @Qualifier — 显式指定 Bean 名

```java
public LoveApp(@Qualifier("dashScopeChatModel") ChatModel dashScopeChatModel) {
    // 精确匹配名为 "dashScopeChatModel" 的 Bean
}
```

- `@Qualifier` 的值 = Bean 注册名（由自动配置类的 `@Bean` 方法名决定）
- 参数变量名**不参与匹配**，但建议与 Bean 名保持一致，提高可读性

### 2. 参数名匹配（隐式）

不加 `@Qualifier` 时，Spring 会尝试用参数名匹配 Bean 名：

```java
// Spring Boot 3.4.x 及以下：参数名 dashscopeChatModel 可以匹配到 Bean
public LoveApp(ChatModel dashscopeChatModel) { ... }
```

**但这在 Spring Boot 3.5.x 中不再可靠**——编译器需要开启 `-parameters` 标志才能保留参数名，否则 Spring 只能看到 `arg0`、`arg1`。

### 3. @Primary — 设置默认 Bean

```java
@Bean
@Primary
public ChatModel dashScopeChatModel() { ... }
```

适合项目中有一个"主力"Bean 的场景，其他地方不加 `@Qualifier` 时自动选它。

## 本项目的实际情况

升级到 Spring Boot 3.5.3 + SAA 1.1.2.0 后，自动配置注册的 Bean 名从 `dashscopeChatModel` 变为 `dashScopeChatModel`（注意大写 S）。

所有注入点必须统一为：
```java
@Qualifier("dashScopeChatModel") ChatModel dashScopeChatModel
```

**要点**：`@Qualifier` 值是**合约**（必须和 Bean 名一字不差），变量名是**风格**（建议一致）。

## 速查表

| 场景 | 方案 |
|------|------|
| 同类型多个 Bean，明确要哪个 | `@Qualifier("beanName")` |
| 同类型多个 Bean，有一个是默认 | 定义方加 `@Primary` |
| 只有一个同类型 Bean | 什么都不用加 |
| `@Resource` 注入 | 默认按名匹配（`@Resource(name = "xxx")`），找不到再按类型 |
